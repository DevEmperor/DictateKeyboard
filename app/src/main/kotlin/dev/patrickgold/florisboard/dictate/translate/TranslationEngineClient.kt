/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The keyboard's side of [TranslationEngineService] (issue #424).
 *
 * Bound on the first request after [release], unbound by [release] — which the translate bar calls when
 * it closes, and which ends the engine's process. Everything here runs on the main thread; the waiting
 * is done by suspending, not by blocking.
 */
object TranslationEngineClient {
    sealed interface Result {
        data class Translated(val text: String) : Result
        data class Failed(val error: Int) : Result
    }

    /** Loading a model takes ~100 ms and a sentence ~100 ms; this only catches a process that hung. */
    private const val TIMEOUT_MS = 15_000L

    private var service: Messenger? = null
    private var connecting: CompletableDeferred<Messenger?>? = null
    private var bound = false
    private var nextRequestId = 1
    private val pending = mutableMapOf<Int, CompletableDeferred<Result>>()

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != TranslationProtocol.RESULT) return
            val result = if (msg.data.containsKey(TranslationProtocol.KEY_ERROR)) {
                Result.Failed(msg.data.getInt(TranslationProtocol.KEY_ERROR))
            } else {
                Result.Translated(msg.data.getString(TranslationProtocol.KEY_TEXT).orEmpty())
            }
            pending.remove(msg.arg1)?.complete(result)
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let(::Messenger)
            connecting?.complete(service)
            connecting = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The engine process died (a native crash, or the system reclaimed it). Whatever was asked
            // is lost; the binding stays, and the system restarts the service for the next request.
            service = null
            failAll()
        }
    }

    suspend fun translate(context: Context, from: String, to: String, text: String): Result {
        val messenger = connect(context) ?: return Result.Failed(TranslationProtocol.ERROR_FAILED)
        val requestId = nextRequestId++
        val answer = CompletableDeferred<Result>()
        pending[requestId] = answer
        val message = Message.obtain(null, TranslationProtocol.TRANSLATE, requestId, 0).apply {
            data = Bundle().apply {
                putString(TranslationProtocol.KEY_FROM, from)
                putString(TranslationProtocol.KEY_TO, to)
                putString(TranslationProtocol.KEY_TEXT, text)
            }
            replyTo = replies
        }
        try {
            messenger.send(message)
        } catch (_: RemoteException) {
            pending.remove(requestId)
            return Result.Failed(TranslationProtocol.ERROR_FAILED)
        }
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: Result.Failed(TranslationProtocol.ERROR_FAILED)
        } finally {
            // Also on cancellation: a superseded request's answer is simply dropped when it arrives.
            pending.remove(requestId)
        }
    }

    /** Unbinds, which lets the engine's process end and take its memory with it. */
    fun release(context: Context) {
        if (!bound) return
        bound = false
        service = null
        connecting?.complete(null)
        connecting = null
        failAll()
        runCatching { context.applicationContext.unbindService(connection) }
    }

    private suspend fun connect(context: Context): Messenger? {
        service?.let { return it }
        val waiting = connecting ?: CompletableDeferred<Messenger?>().also { deferred ->
            connecting = deferred
            if (!bound) {
                val intent = Intent(context.applicationContext, TranslationEngineService::class.java)
                bound = context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    connecting = null
                    deferred.complete(null)
                }
            }
        }
        return withTimeoutOrNull(TIMEOUT_MS) { waiting.await() }
    }

    private fun failAll() {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.complete(Result.Failed(TranslationProtocol.ERROR_FAILED)) }
    }
}
