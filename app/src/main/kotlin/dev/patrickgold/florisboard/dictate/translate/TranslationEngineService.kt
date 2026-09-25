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

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.io.File

/**
 * Runs the Bergamot engine in a process of its own, `:translate` (issue #424).
 *
 * Measured on a Galaxy A55, a loaded pair costs the process 75–150 MB, and the native allocator keeps
 * about 80 MB of it after the models are freed. In the keyboard's process that would be a permanent
 * tax on the one process that must never be killed for memory. Here it lives only while the translate
 * bar is bound: the last unbind ends the service, and [onDestroy] ends the process with it — the only
 * way to be sure the memory is really gone. A crash inside Marian's native code takes this process
 * down, not the keyboard.
 *
 * One request at a time, on one thread: the engine is not thread-safe, and the bar never has more
 * than one question worth answering.
 */
class TranslationEngineService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private var engine = 0L

    /** The directions of the route last used, by id. Anything else is freed before a new load. */
    private val loaded = LinkedHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("bergamot").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what == TranslationProtocol.TRANSLATE) handleTranslate(msg)
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        super.onDestroy()
        thread.quit()
        // The process exists only for this service. Leaving it cached would keep the allocator's share
        // of the models resident until the system got round to reclaiming it.
        Process.killProcess(Process.myPid())
    }

    private fun handleTranslate(msg: Message) {
        val requestId = msg.arg1
        val replyTo = msg.replyTo ?: return
        val data = msg.data
        val from = data.getString(TranslationProtocol.KEY_FROM).orEmpty()
        val to = data.getString(TranslationProtocol.KEY_TO).orEmpty()
        val text = data.getString(TranslationProtocol.KEY_TEXT).orEmpty()

        val reply = Bundle()
        try {
            reply.putString(TranslationProtocol.KEY_TEXT, translate(from, to, text))
        } catch (e: EngineException) {
            reply.putInt(TranslationProtocol.KEY_ERROR, e.code)
        } catch (e: Throwable) {
            Log.w(TAG, "Translation $from → $to failed", e)
            reply.putInt(TranslationProtocol.KEY_ERROR, TranslationProtocol.ERROR_FAILED)
        }
        try {
            replyTo.send(Message.obtain(null, TranslationProtocol.RESULT, requestId, 0).apply { this.data = reply })
        } catch (_: RemoteException) {
            // The keyboard went away while we worked; nobody is waiting for this answer.
        }
    }

    private fun translate(from: String, to: String, text: String): String {
        if (text.isBlank()) return ""
        val route = TranslationCatalog.route(from, to) ?: throw EngineException(TranslationProtocol.ERROR_NOT_INSTALLED)
        if (route.isEmpty()) return text
        BergamotNative.loaded.onFailure { throw EngineException(TranslationProtocol.ERROR_UNSUPPORTED_DEVICE) }
        if (route.any { !TranslationModelManager.isInstalled(this, it) }) {
            throw EngineException(TranslationProtocol.ERROR_NOT_INSTALLED)
        }
        if (engine == 0L) engine = BergamotNative.createEngine(0)
        val models = load(route)
        val input = arrayOf(text.toByteArray())
        val output = if (models.size == 1) {
            BergamotNative.translate(engine, models[0], input)
        } else {
            BergamotNative.pivot(engine, models[0], models[1], input)
        }
        return output.first().toString(Charsets.UTF_8)
    }

    private fun load(route: List<TranslationDirection>): List<Long> {
        val wanted = route.map { it.id }.toSet()
        loaded.keys.filter { it !in wanted }.forEach { id -> loaded.remove(id)?.let(BergamotNative::freeModel) }
        return route.map { direction ->
            loaded.getOrPut(direction.id) {
                val dir = TranslationModelManager.directionDir(this, direction)
                val yaml = BergamotConfig.yaml(direction) { File(dir, it.fileName).absolutePath }
                BergamotNative.loadModel(yaml.toByteArray())
            }
        }
    }

    private class EngineException(val code: Int) : Exception()

    companion object {
        private const val TAG = "TranslationEngine"
    }
}

/** The messages between the keyboard and [TranslationEngineService]. */
internal object TranslationProtocol {
    const val TRANSLATE = 1
    const val RESULT = 2

    const val KEY_FROM = "from"
    const val KEY_TO = "to"
    const val KEY_TEXT = "text"
    const val KEY_ERROR = "error"

    /** A language of the route is not downloaded (any more). */
    const val ERROR_NOT_INSTALLED = 1

    /** The engine is not built for this CPU architecture. */
    const val ERROR_UNSUPPORTED_DEVICE = 2

    /** Marian failed, or the engine process died mid-request. */
    const val ERROR_FAILED = 3
}
