/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

/*
 * JNI bridge to Mozilla's Bergamot translation engine (issue #424).
 *
 * Deliberately thin: one blocking service, models loaded from a YAML config that names the files, and
 * text in and out as UTF-8 byte arrays. Bytes rather than jstring because JNI's "UTF" is modified
 * UTF-8, which splits every emoji into two surrogate halves the tokenizer has never seen.
 *
 * Marian aborts the process on an internal error by default. In a keyboard that would take the whole
 * IME down with it, so exceptions are switched on and every entry point turns them into a Java
 * RuntimeException instead.
 */

#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "common/logging.h"
#include "translator/parser.h"
#include "translator/response_options.h"
#include "translator/service.h"
#include "translator/translation_model.h"

using marian::bergamot::BlockingService;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

namespace {

struct Engine {
    explicit Engine(const BlockingService::Config& config) : service(config) {}
    BlockingService service;
};

struct Model {
    std::shared_ptr<TranslationModel> model;
};

void throwJava(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/RuntimeException");
    if (type != nullptr) env->ThrowNew(type, message);
}

std::string fromBytes(JNIEnv* env, jbyteArray bytes) {
    const jsize length = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray toBytes(JNIEnv* env, const std::string& text) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    }
    return out;
}

std::vector<std::string> fromByteArrays(JNIEnv* env, jobjectArray texts) {
    const jsize count = env->GetArrayLength(texts);
    std::vector<std::string> out;
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto element = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
        out.push_back(fromBytes(env, element));
        env->DeleteLocalRef(element);
    }
    return out;
}

jobjectArray toByteArrays(JNIEnv* env, const std::vector<Response>& responses) {
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(responses.size()), byteArrayClass, nullptr);
    for (size_t i = 0; i < responses.size(); ++i) {
        jbyteArray element = toBytes(env, responses[i].getTranslatedText());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), element);
        env->DeleteLocalRef(element);
    }
    return out;
}

template <typename Block>
auto guarded(JNIEnv* env, Block block) -> decltype(block()) {
    try {
        return block();
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "Bergamot failed with a non-standard exception");
    }
    return {};
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_patrickgold_florisboard_dictate_translate_BergamotNative_createEngine(JNIEnv* env, jclass, jint cacheSize) {
    return guarded(env, [&]() -> jlong {
        marian::setThrowExceptionOnAbort(true);
        BlockingService::Config config;
        config.cacheSize = static_cast<size_t>(cacheSize);
        config.logger.level = "off";
        return reinterpret_cast<jlong>(new Engine(config));
    });
}

JNIEXPORT void JNICALL
Java_dev_patrickgold_florisboard_dictate_translate_BergamotNative_destroyEngine(JNIEnv*, jclass, jlong engine) {
    delete reinterpret_cast<Engine*>(engine);
}

JNIEXPORT jlong JNICALL
Java_dev_patrickgold_florisboard_dictate_translate_BergamotNative_loadModel(JNIEnv* env, jclass, jbyteArray configYaml) {
    return guarded(env, [&]() -> jlong {
        auto options = marian::bergamot::parseOptionsFromString(fromBytes(env, configYaml), /*validate=*/false);
        auto* model = new Model{std::make_shared<TranslationModel>(options)};
        return reinterpret_cast<jlong>(model);
    });
}

JNIEXPORT void JNICALL
Java_dev_patrickgold_florisboard_dictate_translate_BergamotNative_freeModel(JNIEnv*, jclass, jlong model) {
    delete reinterpret_cast<Model*>(model);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_patrickgold_florisboard_dictate_translate_BergamotNative_translate(
    JNIEnv* env, jclass, jlong engine, jlong model, jobjectArray texts) {
    return guarded(env, [&]() -> jobjectArray {
        std::vector<std::string> sources = fromByteArrays(env, texts);
        std::vector<ResponseOptions> options(sources.size());
        auto responses = reinterpret_cast<Engine*>(engine)->service.translateMultiple(
            reinterpret_cast<Model*>(model)->model, std::move(sources), options);
        return toByteArrays(env, responses);
    });
}

JNIEXPORT jobjectArray JNICALL
Java_dev_patrickgold_florisboard_dictate_translate_BergamotNative_pivot(
    JNIEnv* env, jclass, jlong engine, jlong first, jlong second, jobjectArray texts) {
    return guarded(env, [&]() -> jobjectArray {
        std::vector<std::string> sources = fromByteArrays(env, texts);
        std::vector<ResponseOptions> options(sources.size());
        auto responses = reinterpret_cast<Engine*>(engine)->service.pivotMultiple(
            reinterpret_cast<Model*>(first)->model, reinterpret_cast<Model*>(second)->model, std::move(sources), options);
        return toByteArrays(env, responses);
    });
}

}  // extern "C"
