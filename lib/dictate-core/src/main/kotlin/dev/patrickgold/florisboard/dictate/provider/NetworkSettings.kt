/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.Proxy
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** The key and chain behind a client certificate the user picked from Android's own store (#498). */
class ClientCertificate(val privateKey: PrivateKey, val chain: Array<X509Certificate>)

/**
 * Turns a KeyChain alias into its [ClientCertificate] (#498). Android's KeyChain needs a Context, which this
 * module does not have, so the app installs [resolver] at start. It blocks and must never run on the main
 * thread: [AliasKeyManager] only calls it during a TLS handshake, which OkHttp runs on its own threads.
 */
object ClientCertificates {
    @Volatile
    var resolver: ((alias: String) -> ClientCertificate?)? = null

    fun resolve(alias: String): ClientCertificate? =
        if (alias.isBlank()) null else runCatching { resolver?.invoke(alias) }.getOrNull()
}

/**
 * Routes a client the way the user set their network up: through their [proxy], trusting user-installed
 * CA certificates when the account asks for it, and presenting the client certificate behind
 * [clientCertAlias] to a server that asks for one (mutual TLS, #498). The batch client and the real-time
 * WebSocket share this, so a stream takes the same route as the request it stands in for; only the
 * batch path used to, and a user behind a mandatory proxy had real-time fail and fall back every time.
 */
internal fun OkHttpClient.Builder.applyNetworkSettings(
    proxy: ProxyConfig?,
    trustUserCerts: Boolean,
    clientCertAlias: String = "",
): OkHttpClient.Builder = apply {
    proxy?.let { config ->
        proxy(config.toJavaProxy())
        if (config.type == Proxy.Type.HTTP && config.hasCredentials) {
            proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(config.username!!, config.password!!))
                    .build()
            }
        }
        // SOCKS proxy authentication is not handled here (OkHttp limitation). Add a
        // java.net.Authenticator if SOCKS-with-credentials support is ever required.
    }
    if (trustUserCerts || clientCertAlias.isNotBlank()) {
        applyTls(trustUserCerts, clientCertAlias)
    }
}

/**
 * One TLS context for both settings, since a client has only one: the trust anchors, and the key manager
 * that answers a server's certificate request.
 *
 * Trust: user-installed CA certificates as well as system ones when [trustUserCerts] (issue #137). Android's
 * default trust manager (API 24+) honours only system CAs, but the `AndroidCAStore` keystore exposes the
 * combined system + user trust anchors, so we build an [X509TrustManager] from it and install it on this
 * client only. Otherwise the platform default.
 *
 * Client certificate: [AliasKeyManager] for [clientCertAlias]. Without one a server behind mutual TLS, such
 * as Cloudflare Access with an mTLS rule, answers with its login page instead of the API.
 *
 * Best-effort: if the platform store is unavailable for any reason, the default configuration stays.
 */
private fun OkHttpClient.Builder.applyTls(trustUserCerts: Boolean, clientCertAlias: String) {
    runCatching {
        val anchors = if (trustUserCerts) KeyStore.getInstance("AndroidCAStore").apply { load(null) } else null
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(anchors)
        }
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: return@runCatching
        val keyManagers: Array<KeyManager>? =
            clientCertAlias.takeIf { it.isNotBlank() }?.let { arrayOf(AliasKeyManager(it)) }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), null)
        }
        sslSocketFactory(sslContext.socketFactory, trustManager)
    }
}

/**
 * Presents the certificate behind one KeyChain [alias], fetched the first time a server asks for it.
 *
 * Fetched lazily on purpose: KeyChain blocks and refuses the main thread, and a client is sometimes built
 * there, but a handshake always runs on OkHttp's own threads. A certificate that cannot be had (removed,
 * or the app's access revoked) means none is presented, and the server's refusal is the error the user
 * sees; a later client tries again, because the outcome is not kept.
 */
internal class AliasKeyManager(
    private val alias: String,
    private val resolve: (String) -> ClientCertificate? = ClientCertificates::resolve,
) : X509ExtendedKeyManager() {

    @Volatile private var resolved: ClientCertificate? = null

    private fun certificate(): ClientCertificate? = resolved ?: resolve(alias)?.also { resolved = it }

    private fun aliasIfAvailable(): String? = alias.takeIf { certificate() != null }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
        aliasIfAvailable()

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        aliasIfAvailable()

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        aliasIfAvailable()?.let { arrayOf(it) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) certificate()?.chain else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) certificate()?.privateKey else null

    // A client never serves.
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}
