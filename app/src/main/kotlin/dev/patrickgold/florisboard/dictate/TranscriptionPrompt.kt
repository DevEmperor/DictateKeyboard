/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.data.prompts.CustomWordList
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults

// One definition for every path that transcribes: the keyboard, a long dictation's segments, a shared file,
// and the watch, whether it dictates through the phone or calls the provider itself. The watch's settings
// are made from these, so every preference read here is one the watch publisher listens to
// (WearPublisherCoverageTest reads this file).

/**
 * The style prompt WITHOUT the appended custom-words glossary — the predefined per-language sentence or
 * the user's custom style prompt. This is the part a Whisper-style model echoes on silence, so the
 * prompt-echo guard (#77) compares against it rather than the full prompt (whose trailing glossary
 * would otherwise throw off the overlap check).
 *
 * [language] picks the predefined sentence, and has to be the language the audio goes out with: a
 * sentence in another one argues against it (#275). A shared file has a language of its own (#461).
 */
fun transcriptionStyleBasePrompt(
    prefs: FlorisPreferenceModel,
    language: String = prefs.dictate.activeInputLanguage.get(),
): String? = when (prefs.dictate.stylePromptSelection.get()) {
    DictatePromptDefaults.SELECTION_PREDEFINED -> DictatePromptDefaults.punctuationPromptFor(language)
    DictatePromptDefaults.SELECTION_CUSTOM -> prefs.dictate.stylePromptCustom.get().takeIf { it.isNotBlank() }
    else -> null
}

/**
 * Style/punctuation prompt sent with the transcription request (independent of rewording). The user's
 * custom words (roadmap 11.12) are appended on top of whichever style prompt is active, so names/jargon
 * are spelled correctly even with the predefined punctuation prompt or with none.
 */
fun transcriptionStylePrompt(
    prefs: FlorisPreferenceModel,
    language: String = prefs.dictate.activeInputLanguage.get(),
): String? = DictatePromptDefaults.appendCustomWords(transcriptionStyleBasePrompt(prefs, language), prefs.dictate.customWords.get())

/**
 * The custom words one by one (issue #466), for
 * [TranscriptionRequest.vocabulary][dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest.vocabulary]
 * and the realtime session: the providers whose field takes a list of terms get them this way, everyone
 * else inside [transcriptionStylePrompt].
 */
fun customWordList(prefs: FlorisPreferenceModel): List<String> = CustomWordList.parse(prefs.dictate.customWords.get())
