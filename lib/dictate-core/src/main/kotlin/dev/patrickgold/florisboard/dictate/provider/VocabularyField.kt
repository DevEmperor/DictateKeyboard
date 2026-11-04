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

/**
 * What one provider field takes of the user's custom words (issue #466).
 *
 * The words travel two ways. Inside the transcription prompt they reach every field that takes prose
 * (Whisper's `prompt`, Soniox's `context`, Gemini's instruction). As [TranscriptionRequest.vocabulary] they
 * reach the fields that take a list of terms, and each of those has rules a prompt does not, and refuses the
 * whole request when one is broken. [fit] keeps what a field takes, in the user's order:
 *  - a term longer than [maxChars], of more than [maxWords] words, or holding a character from [refused] is
 *    left out rather than shortened, since half a name biases towards the wrong word;
 *  - the list ends at [maxTerms], or before the term that would take it past [maxTotalWords] or
 *    [maxTotalBytes], so the first words of the list are the ones that count.
 *
 * Each limit is the vendor's own, as their docs give it and the endpoint answered it on 2026-11-04. Where
 * a vendor counts in a unit the app cannot reproduce (Deepgram's tokens), the limit here sits well inside
 * the measured edge, and [OpenAiCompatibleClient.transcribe] asks once more without the list if it is
 * refused all the same: a word list must never cost a dictation.
 *
 * That each field arrives was measured with the provider check's word probes on 2026-11-04, natural speech
 * of names a model rarely spells on its own: every field here changed a transcript for the better on at
 * least one model, without a single refusal. Where none changed, the field was shown to be read by an
 * over-limit list, which the socket refused by name: Deepgram's Flux multilingual, AssemblyAI's
 * Universal-Streaming models.
 */
internal data class VocabularyField(
    val maxTerms: Int,
    val maxChars: Int = Int.MAX_VALUE,
    val maxWords: Int = Int.MAX_VALUE,
    val maxTotalWords: Int = Int.MAX_VALUE,
    val maxTotalBytes: Int = Int.MAX_VALUE,
    val refused: String = "",
) {
    fun fit(words: List<String>): List<String> {
        val terms = mutableListOf<String>()
        var totalWords = 0
        var totalBytes = 0
        for (word in words) {
            if (terms.size == maxTerms) break
            val term = word.trim()
            if (term.isEmpty() || term in terms) continue
            if (term.length > maxChars || term.any { it in refused || it == '\n' || it == '\r' }) continue
            val wordCount = term.split(WHITESPACE).size
            if (wordCount > maxWords) continue
            val bytes = term.toByteArray(Charsets.UTF_8).size
            if (totalWords + wordCount > maxTotalWords || totalBytes + bytes > maxTotalBytes) break
            terms += term
            totalWords += wordCount
            totalBytes += bytes
        }
        return terms
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * OpenAI's `keywords` (gpt-transcribe and gpt-live-transcribe, streaming only here). No count is
         * documented; the whole request is refused for a keyword holding `<`, `>` or a line break.
         */
        val OPENAI = VocabularyField(maxTerms = 100, refused = "<>")

        /** Google's `custom_vocabulary` and Live's `customVocabulary`: up to 1000, "best results with up to 100". */
        val GEMINI = VocabularyField(maxTerms = 100)

        /** Azure's `phraseList`: "shouldn't have more than 2000 phrases", and more costs quality and latency. */
        val AZURE = VocabularyField(maxTerms = 100)

        /** xAI's `keyterm`, file and stream alike: up to 100, 50 characters each. */
        val XAI = VocabularyField(maxTerms = 100, maxChars = 50)

        /**
         * Deepgram's `keyterm` (Nova-3 and Flux): 500 tokens across all of them, by a count of Deepgram's
         * own. Measured: 84 ordinary glossary entries (933 bytes) were taken, 88 (966 bytes) refused, and
         * digits cost far more ("Siobhan1" to "Siobhan83" was refused at 738 bytes). Deepgram advises
         * 20 to 50 terms, and 50 terms or 600 bytes stay clear of the edge. A single term may have 100
         * characters (Flux said so when refusing a longer one).
         */
        val DEEPGRAM_KEYTERM = VocabularyField(maxTerms = 50, maxChars = 100, maxTotalBytes = 600)

        /** Deepgram's older `keywords` (Nova-2, which refuses `keyterm`): 130 were taken, words and phrases. */
        val DEEPGRAM_KEYWORDS = VocabularyField(maxTerms = 100)

        /**
         * AssemblyAI's `keyterms_prompt` for files: at most 6 words a term, and 200 words in all on
         * Universal-2 (counted across terms: 67 terms of three words were refused). The Pro models take
         * 1000, but Universal-3 Pro's prompt budget was full at 1000 short terms, and 200 fit every model.
         */
        val ASSEMBLYAI = VocabularyField(maxTerms = 200, maxWords = 6, maxTotalWords = 200)

        /**
         * AssemblyAI's `keyterms_prompt` on the streaming socket: 100 terms, 50 characters each. A list of
         * 101 ends the session with error 3006 on every streaming model, Universal-Streaming English too.
         */
        val ASSEMBLYAI_STREAMING = VocabularyField(maxTerms = 100, maxChars = 50)

        /**
         * ElevenLabs' `keyterms` for files, one form field each: up to 1000, 50 characters and 5 words each,
         * none of `<>{}[]\`; the endpoint refused each of those by name, and a JSON list as one field for its
         * brackets. Past 100 terms every request bills at least 20 seconds, so a dictation never sends more.
         */
        val ELEVENLABS = VocabularyField(maxTerms = 100, maxChars = 50, maxWords = 5, refused = "<>{}[]\\")

        /**
         * ElevenLabs' `keyterms` on the streaming socket: 50 terms of 20 characters, as the socket says when
         * it refuses a 51st or a 21st.
         */
        val ELEVENLABS_STREAMING = VocabularyField(maxTerms = 50, maxChars = 20, maxWords = 5, refused = "<>{}[]\\")

        /**
         * Mistral's `context_bias` (Voxtral): "up to 100" entries, which share the model's context window
         * with the audio; over it the request is refused, never shortened.
         */
        val MISTRAL = VocabularyField(maxTerms = 100)

        /** Soniox's `context.terms` on the socket: the whole context is 8000 tokens, about 10 000 characters. */
        val SONIOX = VocabularyField(maxTerms = 500, maxTotalBytes = 6000)
    }
}
