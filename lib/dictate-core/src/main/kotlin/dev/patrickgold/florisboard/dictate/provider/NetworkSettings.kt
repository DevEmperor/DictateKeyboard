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
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Routes a client the way the user set their network up: through their [proxy], and trusting
 * user-installed CA certificates when the account asks for it. The batch client and the real-time
 * WebSocket share this, so a stream takes the same route as the request it stands in for; only the
 * batch path used to, and a user behind a mandatory proxy had real-time fail and fall back every time.
 */
internal fun OkHttpClient.Builder.applyNetworkSettings(
    proxy: ProxyConfig?,
    trustUserCerts: Boolean,
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
    if (trustUserCerts) {
        applyUserCertTrust()
    }
}

/**
 * Makes this client trust user-installed CA certificates as well as system ones (issue #137).
 * Android's default trust manager (API 24+) honours only system CAs, but the `AndroidCAStore`
 * keystore exposes the combined system + user trust anchors, so we build an [X509TrustManager]
 * from it and install it on this client only. Best-effort: if the platform store is unavailable
 * for any reason, the default (system-CAs-only) trust configuration stays in place.
 */
private fun OkHttpClient.Builder.applyUserCertTrust() {
    runCatching {
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: return@runCatching
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        sslSocketFactory(sslContext.socketFactory, trustManager)
    }
}
