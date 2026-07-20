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

import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Marker attached only to HTTP calls whose phase timings are useful in exported debug logs. */
internal data class HttpCallDiagnostics(val label: String) {
    companion object {
        fun listenerFor(request: Request): EventListener =
            request.tag(HttpCallDiagnostics::class.java)
                ?.let(::HttpTimingEventListener)
                ?: EventListener.NONE
    }
}

/**
 * Privacy-safe OkHttp phase diagnostics. It deliberately logs address families rather than addresses,
 * and never logs URLs, headers, request bodies, audio, credentials, responses or transcripts.
 */
private class HttpTimingEventListener(
    private val diagnostics: HttpCallDiagnostics,
) : EventListener() {
    private var callStartedNanos = 0L
    private var dnsStartedNanos = 0L
    private var tlsStartedNanos = 0L
    private var uploadStartedNanos = 0L
    private var responseHeadersStartedNanos = 0L
    private var responseBodyStartedNanos = 0L
    private val connectAttempts = ConcurrentHashMap<ConnectRoute, ConnectAttempt>()
    private val hadConnectAttempt = AtomicBoolean(false)

    override fun callStart(call: Call) {
        callStartedNanos = System.nanoTime()
        DictateHttpLog.info("${diagnostics.label} networkAttempt started")
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartedNanos = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        val ipv4 = inetAddressList.count { it is Inet4Address }
        val ipv6 = inetAddressList.count { it is Inet6Address }
        DictateHttpLog.info(
            "${diagnostics.label} dnsMs=${phaseMs(dnsStartedNanos)} addresses=IPv4:$ipv4,IPv6:$ipv6",
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        hadConnectAttempt.set(true)
        val family = addressFamily(inetSocketAddress.address)
        connectAttempts[ConnectRoute(inetSocketAddress, proxy)] =
            ConnectAttempt(startedNanos = System.nanoTime(), family = family)
        DictateHttpLog.info(
            "${diagnostics.label} connectStartMs=${sinceCallMs()} family=$family",
        )
    }

    override fun secureConnectStart(call: Call) {
        tlsStartedNanos = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        DictateHttpLog.info("${diagnostics.label} tlsMs=${phaseMs(tlsStartedNanos)}")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        val attempt = connectAttempts.remove(ConnectRoute(inetSocketAddress, proxy))
        DictateHttpLog.info(
            "${diagnostics.label} connectMs=${phaseMs(attempt?.startedNanos ?: 0L)} " +
                "family=${attempt?.family ?: addressFamily(inetSocketAddress.address)} " +
                "protocol=${protocol ?: "unknown"}",
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        val attempt = connectAttempts.remove(ConnectRoute(inetSocketAddress, proxy))
        DictateHttpLog.warn(
            "${diagnostics.label} connectFailedMs=${phaseMs(attempt?.startedNanos ?: 0L)} " +
                "family=${attempt?.family ?: addressFamily(inetSocketAddress.address)} " +
                "error=${ioe.javaClass.simpleName}",
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val family = addressFamily(connection.route().socketAddress.address)
        DictateHttpLog.info(
            "${diagnostics.label} connectionAcquiredMs=${sinceCallMs()} family=$family " +
                "protocol=${connection.protocol()} reused=${!hadConnectAttempt.get()}",
        )
    }

    override fun requestBodyStart(call: Call) {
        uploadStartedNanos = System.nanoTime()
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        DictateHttpLog.info(
            "${diagnostics.label} uploadMs=${phaseMs(uploadStartedNanos)} requestBytes=$byteCount",
        )
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersStartedNanos = System.nanoTime()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        DictateHttpLog.info(
            "${diagnostics.label} ttfbMs=${sinceCallMs()} " +
                "responseHeadersMs=${phaseMs(responseHeadersStartedNanos)} status=${response.code}",
        )
    }

    override fun responseBodyStart(call: Call) {
        responseBodyStartedNanos = System.nanoTime()
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        DictateHttpLog.info(
            "${diagnostics.label} responseBodyMs=${phaseMs(responseBodyStartedNanos)} " +
                "responseBytes=$byteCount",
        )
    }

    override fun callEnd(call: Call) {
        DictateHttpLog.info("${diagnostics.label} networkCompletedMs=${sinceCallMs()}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        DictateHttpLog.warn(
            "${diagnostics.label} networkFailedMs=${sinceCallMs()} error=${ioe.javaClass.simpleName}",
        )
    }

    private fun sinceCallMs(): Long = phaseMs(callStartedNanos)

    private fun phaseMs(startedNanos: Long): Long =
        if (startedNanos == 0L) 0L
        else TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    private fun addressFamily(address: InetAddress?): String = when (address) {
        is Inet4Address -> "IPv4"
        is Inet6Address -> "IPv6"
        else -> "unknown"
    }

    private data class ConnectRoute(val address: InetSocketAddress, val proxy: Proxy)
    private data class ConnectAttempt(val startedNanos: Long, val family: String)
}

/** Android log facade that is harmless in local JVM tests where android.util.Log is only a stub. */
internal object DictateHttpLog {
    private const val TAG = "DictateHTTP"

    fun info(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
            // android.jar's local-test stub throws; device builds write the normal Logcat entry.
        }
    }

    fun warn(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: RuntimeException) {
            // android.jar's local-test stub throws; device builds write the normal Logcat entry.
        }
    }
}
