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

/**
 * JNI surface of `libdictate_bergamot.so` — Mozilla's Bergamot engine behind our own bridge
 * (issue #424; built by `tools/bergamot/build-android.sh`).
 *
 * Handles are raw native pointers: an engine owns no models, a model can be used by any engine, and
 * nothing here is thread-safe — one caller at a time per engine. Text crosses as UTF-8 bytes (see the
 * bridge for why). Every call can throw a [RuntimeException] carrying Marian's error message.
 */
internal object BergamotNative {
    /** Loads the library once; the failure is kept, so a missing ABI is reported instead of retried. */
    val loaded: Result<Unit> by lazy { runCatching { System.loadLibrary("dictate_bergamot") } }

    @JvmStatic external fun createEngine(cacheSize: Int): Long

    @JvmStatic external fun destroyEngine(engine: Long)

    /** [configYaml] is a Marian/Bergamot config naming the model, vocab and shortlist files. */
    @JvmStatic external fun loadModel(configYaml: ByteArray): Long

    @JvmStatic external fun freeModel(model: Long)

    @JvmStatic external fun translate(engine: Long, model: Long, texts: Array<ByteArray>): Array<ByteArray>

    /** Translates with [first], then [second] — source → English → target. */
    @JvmStatic external fun pivot(engine: Long, first: Long, second: Long, texts: Array<ByteArray>): Array<ByteArray>
}
