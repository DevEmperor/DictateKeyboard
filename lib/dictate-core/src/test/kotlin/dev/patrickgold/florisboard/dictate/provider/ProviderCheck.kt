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

import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours

/**
 * The provider check: every provider the app ships, every model it offers, every feature it uses, asked of
 * the live endpoints through the app's own clients, so what is measured is what the app sends.
 *
 * It never runs on its own. Jannis decided (2026-11-03) that it is run by hand, once a month, together,
 * with the results looked at before anything changes. The unit-test task skips it unless
 * `DICTATE_PROVIDER_CHECK` names the keys file; `.claude/provider-check/run.sh` sets that up and says how.
 *
 * Environment:
 * - `DICTATE_PROVIDER_CHECK`: the keys file, `NAME=value` per line (OPENAI, GROQ, …, AZURE_URL).
 * - `DICTATE_PROVIDER_CHECK_FIXTURES`: en.wav, de.wav, names.wav, en.{mp3,m4a,ogg,flac,webm}, {en,de}_<rate>.pcm.
 * - `DICTATE_PROVIDER_CHECK_OUT`: where report.md and results.json go.
 * - `DICTATE_PROVIDER_CHECK_ONLY`: preset ids to check, comma-separated; all when unset.
 * - `DICTATE_PROVIDER_CHECK_PARTS`: models,batch,formats,chat,reasoning,realtime; all when unset.
 * - `DICTATE_PROVIDER_CHECK_MODELS`: optional file of `preset<TAB>role<TAB>model` lines for models to check
 *   on top of the curated ones (role: chat, transcription, realtime). For providers that curate none.
 *
 * Nothing it writes contains a key: provider messages are cut short and scrubbed of the key before they are
 * kept, because some providers quote a key back in their refusal.
 */
class ProviderCheck : FunSpec({

    val keysPath = System.getenv("DICTATE_PROVIDER_CHECK")

    test("every provider, model and feature against the live endpoints")
        .config(enabled = keysPath != null, timeout = 3.hours, invocationTimeout = 3.hours) {
            val check = Check(
                keys = File(keysPath!!).readLines()
                    .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
                    .associate { (k, v) -> k.trim() to v.trim() },
                fixtures = File(System.getenv("DICTATE_PROVIDER_CHECK_FIXTURES")),
                out = File(System.getenv("DICTATE_PROVIDER_CHECK_OUT")).apply { mkdirs() },
                only = System.getenv("DICTATE_PROVIDER_CHECK_ONLY")?.split(",")?.map { it.trim() }?.toSet(),
                parts = System.getenv("DICTATE_PROVIDER_CHECK_PARTS")?.split(",")?.map { it.trim() }?.toSet()
                    ?: setOf("models", "batch", "formats", "chat", "reasoning", "realtime"),
                extraModels = System.getenv("DICTATE_PROVIDER_CHECK_MODELS")?.let { File(it) }?.takeIf { it.isFile }
                    ?.readLines()?.filter { it.isNotBlank() && !it.startsWith("#") }
                    ?.mapNotNull { l -> l.split("\t").takeIf { it.size >= 3 }?.map { it.trim() } }
                    ?: emptyList(),
            )
            check.run()
        }
})

@Serializable
private data class Row(
    val provider: String,
    val part: String,
    val model: String,
    val probe: String,
    /** OK, FAIL, REFUSED, EMPTY, SKIP or INFO. */
    val outcome: String,
    val detail: String,
    val ms: Long = 0,
)

private class Check(
    val keys: Map<String, String>,
    val fixtures: File,
    val out: File,
    val only: Set<String>?,
    val parts: Set<String>,
    val extraModels: List<List<String>>,
) {
    private val rows = mutableListOf<Row>()
    private val facts = StringBuilder()
    private val warnings = mutableListOf<String>()

    private val enText = "Hello, this is a short test of the Dictate keyboard. Please meet me at the station " +
        "tomorrow at half past nine."
    private val deText = "Hallo, das ist ein kurzer Test der Dictate Tastatur. Wir treffen uns morgen um halb " +
        "zehn am Bahnhof."

    /** Scale the reasoning matrix walks; null is the field left out. */
    private val efforts = listOf(null, "none", "minimal", "low", "medium", "high", "xhigh", "max")

    private val keyNames = mapOf("ovhcloud" to "OVH")

    suspend fun run() {
        DictateHttpLog.warnSink = { synchronized(warnings) { warnings += it } }
        try {
            ProviderRegistry.presets
                .filter { it.id !in setOf("cloud", "ollama", "local") }
                .filter { only == null || it.id in only }
                // Written after every provider, so a run that dies half-way still leaves what it found.
                .forEach { preset -> checkProvider(preset); write() }
        } finally {
            DictateHttpLog.warnSink = null
            write()
        }
    }

    private suspend fun checkProvider(preset: ProviderPreset) {
        val keyName = keyNames[preset.id] ?: preset.id.uppercase()
        val key = keys[keyName]
        // Azure has no address of its own; elsewhere a `<NAME>_URL` stands for the host the account lives on
        // (SiliconFlow's international accounts are on .com, the preset knows only .cn).
        val baseUrl = keys["${keyName}_URL"]?.takeIf { it.isNotBlank() }
        if (baseUrl != null) add(preset, "setup", "-", "base URL", "INFO", "checked at $baseUrl instead of ${preset.baseUrl.ifEmpty { "–" }}")
        describe(preset)
        if (key.isNullOrBlank()) {
            add(preset, "setup", "-", "key", "SKIP", "no key in the keys file")
            return
        }
        val client = OpenAiCompatibleClient.from(preset, key, baseUrlOverride = baseUrl, timeoutSeconds = 90)
        val scrub = { s: String -> scrub(s, key) }

        val live = if ("models" in parts || preset.capabilities.chat) models(preset, client, scrub) else emptyList()

        if (preset.capabilities.transcription) {
            val sttModels = (listOfNotNull(preset.defaultTranscriptionModel) + preset.curatedTranscriptionModels +
                extra(preset, "transcription")).distinct()
            if ("batch" in parts) sttModels.forEach { batch(preset, client, it, scrub) }
            if ("formats" in parts) sttModels.firstOrNull()?.let { formats(preset, client, it, scrub) }
        }
        if (preset.capabilities.chat) {
            val chatModels = (listOfNotNull(preset.defaultChatModel) + preset.curatedChatModels +
                extra(preset, "chat")).distinct()
            if (chatModels.isEmpty()) {
                add(preset, "chat", "-", "models", "INFO",
                    "no curated chat model; live list has ${live.size}. Name some in the models file to check them.")
            }
            chatModels.forEach { model ->
                if ("chat" in parts) chat(preset, client, model, scrub)
                if ("reasoning" in parts) reasoning(preset, client, model, scrub)
            }
        }
        val rtApi = preset.realtimeApi
        if ("realtime" in parts && preset.supportsRealtime && rtApi != null) {
            (listOfNotNull(preset.defaultRealtimeModel) + preset.curatedRealtimeModels + extra(preset, "realtime"))
                .distinct()
                .forEach { model ->
                    try {
                        realtime(preset, rtApi, key, baseUrl, model, scrub)
                    } catch (e: Exception) {
                        add(preset, "realtime", model, "session", "FAIL", scrub(e.toString()))
                    }
                }
        }
    }

    private fun extra(preset: ProviderPreset, role: String) =
        extraModels.filter { it[0] == preset.id && it[1] == role }.map { it[2] }

    /** What the code claims about [preset], before anything is asked of it. */
    private fun describe(p: ProviderPreset) {
        facts.appendLine("### ${p.displayName} (`${p.id}`)")
        facts.appendLine()
        facts.appendLine("- chat: ${p.capabilities.chat}, transcription: ${p.capabilities.transcription}, " +
            "wire format: `${p.transcriptionApi}`, live model list: ${p.supportsDynamicModels}")
        facts.appendLine("- default chat: `${p.defaultChatModel}` (effort when Off: `${p.defaultChatReasoningEffort}`), " +
            "curated chat: ${p.curatedChatModels.joinToString { "`$it`" }.ifEmpty { "–" }}")
        facts.appendLine("- default transcription: `${p.defaultTranscriptionModel}`, curated: " +
            p.curatedTranscriptionModels.joinToString { "`$it`" }.ifEmpty { "–" })
        facts.appendLine("- realtime: ${p.supportsRealtime} (`${p.realtimeApi}`), default `${p.defaultRealtimeModel}`, " +
            "curated: ${p.curatedRealtimeModels.joinToString { "`$it`" }.ifEmpty { "–" }}")
        facts.appendLine("- containers: ${p.acceptedAudioContainers.joinToString().ifEmpty { "unknown" }}; " +
            "regions: ${p.regions.joinToString { it.id }.ifEmpty { "–" }}; EU by default: ${p.hostedInEu}; " +
            "custom base URL: ${p.allowsCustomBaseUrl}")
        facts.appendLine()
    }

    private suspend fun models(preset: ProviderPreset, client: OpenAiCompatibleClient, scrub: (String) -> String): List<String> {
        val (ids, ms, error) = timed { client.listModels().map { it.id } }
        if (ids == null) {
            add(preset, "models", "-", "list", "FAIL", scrub(error.orEmpty()), ms)
            return emptyList()
        }
        val curated = (preset.curatedChatModels + preset.curatedTranscriptionModels + preset.curatedRealtimeModels +
            listOfNotNull(preset.defaultChatModel, preset.defaultTranscriptionModel, preset.defaultRealtimeModel)).toSet()
        val missing = curated.filter { it !in ids }
        val new = ids.filter { it !in curated }.sorted()
        add(preset, "models", "-", "list", "OK", "${ids.size} live", ms)
        if (preset.supportsDynamicModels || missing.isNotEmpty()) {
            add(preset, "models", "-", "curated not listed", if (missing.isEmpty()) "OK" else "INFO",
                missing.joinToString().ifEmpty { "none" })
        }
        add(preset, "models", "-", "listed, not curated", "INFO",
            if (new.size > 60) "${new.size} ids (too many to list)" else new.joinToString().ifEmpty { "none" })
        return ids
    }

    private suspend fun batch(preset: ProviderPreset, client: OpenAiCompatibleClient, model: String, scrub: (String) -> String) {
        suspend fun once(probe: String, file: String, language: String?, prompt: String?, expected: String?, mustHave: List<String>) {
            val (text, ms, error) = timed {
                client.transcribe(
                    TranscriptionRequest(audioFile = File(fixtures, file), model = model, language = language, prompt = prompt),
                    onRetry = {},
                ).text
            }
            if (text == null) {
                add(preset, "batch", model, probe, "FAIL", scrub(error.orEmpty()), ms)
                return
            }
            val score = expected?.let { " · ${overlap(expected, text)}% of words" }.orEmpty()
            val found = mustHave.takeIf { it.isNotEmpty() }?.let { w ->
                " · names: " + w.joinToString { if (text.contains(it, ignoreCase = true)) "$it ✓" else "$it ✗" }
            }.orEmpty()
            add(preset, "batch", model, probe, "OK", "\"${scrub(text).take(140)}\"$score$found", ms)
        }
        once("en, auto", "en.wav", null, null, enText, emptyList())
        once("de, language=de", "de.wav", "de", null, deText, emptyList())
        once("de, auto", "de.wav", null, null, deText, emptyList())
        once("names, no prompt", "names.wav", null, null, null, listOf("Siobhan", "Niamh"))
        once("names, prompt", "names.wav", null, "Names: Siobhan, Niamh.", null, listOf("Siobhan", "Niamh"))
    }

    private suspend fun formats(preset: ProviderPreset, client: OpenAiCompatibleClient, model: String, scrub: (String) -> String) {
        for (ext in listOf("wav", "mp3", "m4a", "ogg", "flac", "webm")) {
            val (text, ms, error) = timed {
                client.transcribe(TranscriptionRequest(audioFile = File(fixtures, "en.$ext"), model = model), onRetry = {}).text
            }
            val claimed = preset.acceptedAudioContainers.joinToString { it.name.lowercase() }.ifEmpty { "unknown" }
            if (text == null) add(preset, "formats", model, ext, "FAIL", "${scrub(error.orEmpty())} · claimed: $claimed", ms)
            else add(preset, "formats", model, ext, "OK", "${overlap(enText, text)}% of words · claimed: $claimed", ms)
        }
    }

    private suspend fun chat(preset: ProviderPreset, client: OpenAiCompatibleClient, model: String, scrub: (String) -> String) {
        val request = DictatePromptDefaults.buildRewordingRequest(
            "Correct all spelling and grammar mistakes. Return only the corrected text.",
            null,
            "this are a short test sentense with two mistake.",
        )
        val (reply, ms, error) = timed { client.complete(ChatRequest.ofUser(model, request)).text }
        if (reply == null) add(preset, "chat", model, "rewording", "FAIL", scrub(error.orEmpty()), ms)
        else add(preset, "chat", model, "rewording", "OK", "\"${scrub(DictatePromptDefaults.stripTextTags(reply)).take(120)}\"", ms)
    }

    /** One request per value, none of [OpenAiCompatibleClient.complete]'s retries: what the endpoint itself does. */
    private suspend fun reasoning(preset: ProviderPreset, client: OpenAiCompatibleClient, model: String, scrub: (String) -> String) {
        for (effort in efforts) {
            val label = effort ?: "(left out)"
            val (result, ms, error) = timed {
                client.completeOnce(ChatRequest.ofUser(model, "Reply with the single word OK.", effort))
            }
            when (result) {
                is OpenAiCompatibleClient.Completion.Answer -> add(preset, "reasoning", model, label, "OK",
                    "completion tokens: ${result.result.usage?.completionTokens ?: "?"}", ms)
                is OpenAiCompatibleClient.Completion.Empty -> add(preset, "reasoning", model, label, "EMPTY",
                    scrub(result.message), ms)
                null -> add(preset, "reasoning", model, label, "REFUSED", scrub(error.orEmpty()), ms)
            }
        }
    }

    private fun realtime(
        preset: ProviderPreset,
        api: RealtimeApi,
        key: String,
        baseUrl: String?,
        model: String,
        scrub: (String) -> String,
    ) {
        for ((lang, expected) in listOf("en" to enText, "de" to deText)) {
            val rate = RealtimeClient.sampleRateFor(api)
            val pcm = File(fixtures, "${lang}_$rate.pcm").readBytes()
            val finals = StringBuilder()
            var lastPartial = ""
            var failure: Throwable? = null
            val lastText = AtomicLong(0)
            val closed = CountDownLatch(1)
            val started = System.currentTimeMillis()
            val session = RealtimeClient.open(
                api = api, apiKey = key, model = model, language = lang,
                callbacks = object : RealtimeCallbacks {
                    override fun onPartial(text: String) { lastPartial = text; lastText.set(System.currentTimeMillis()) }
                    override fun onFinalSegment(text: String) {
                        synchronized(finals) { finals.append(text).append(' ') }
                        lastText.set(System.currentTimeMillis())
                    }
                    override fun onError(t: Throwable) { failure = t; closed.countDown() }
                    override fun onClosed() { closed.countDown() }
                },
                baseUrl = baseUrl,
            )
            // 100 ms chunks in real time, as a microphone delivers them: at twice the speed Gemini Live heard
            // nothing and AssemblyAI's multilingual model next to nothing (2026-11-03).
            val chunk = rate / 10 * 2
            var at = 0
            while (at < pcm.size) {
                val len = minOf(chunk, pcm.size - at)
                session.sendAudio(pcm.copyOfRange(at, at + len), len)
                at += len
                Thread.sleep(100)
            }
            session.finish()
            // Some sockets stay open after the last final (the app closes them itself): a final followed by
            // 3 s of quiet counts as the end, a socket that says nothing at all gets 30 s.
            var ended = false
            val giveUp = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < giveUp) {
                if (closed.await(250, TimeUnit.MILLISECONDS)) { ended = true; break }
                val quiet = System.currentTimeMillis() - lastText.get()
                if (synchronized(finals) { finals.isNotEmpty() } && quiet > 3_000) break
            }
            if (!ended) session.cancel()
            val ms = System.currentTimeMillis() - started
            val text = finals.toString().trim().ifEmpty { lastPartial }
            when {
                failure != null -> add(preset, "realtime", model, lang, "FAIL", scrub(failure.toString()), ms)
                text.isBlank() -> add(preset, "realtime", model, lang, "EMPTY", if (ended) "closed without text" else "no end after 30 s", ms)
                else -> add(preset, "realtime", model, lang, "OK",
                    "\"${scrub(text).take(120)}\" · ${overlap(expected, text)}% of words${if (ended) "" else " · no close"}", ms)
            }
        }
    }

    private data class Timed<T>(val value: T?, val ms: Long, val error: String?)

    private suspend fun <T> timed(block: suspend () -> T): Timed<T> {
        synchronized(warnings) { warnings.clear() }
        val start = System.currentTimeMillis()
        return try {
            Timed(block(), System.currentTimeMillis() - start, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? DictateApiException)?.let { " [${it.httpStatus ?: it.kind}]" }.orEmpty()
            Timed(null, System.currentTimeMillis() - start, "${e.message ?: e::class.simpleName}$status")
        }
    }

    private fun add(preset: ProviderPreset, part: String, model: String, probe: String, outcome: String, detail: String, ms: Long = 0) {
        // A warning the client logged on the way is how a refused request taking another route shows.
        val warned = synchronized(warnings) { warnings.toList().also { warnings.clear() } }
            .filter { "fallingBack" in it || "refused" in it || "rejected" in it }
            .joinToString(" | ") { it.take(160) }
        val row = Row(preset.id, part, model, probe, outcome, (detail + if (warned.isEmpty()) "" else " ⚠ $warned").replace("\n", " "), ms)
        rows += row
        println("[check] ${row.provider} ${row.part} ${row.model} ${row.probe}: ${row.outcome} ${row.detail.take(160)}")
    }

    private fun overlap(expected: String, actual: String): Int {
        fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.isNotBlank() }
        val want = words(expected)
        val got = words(actual).toMutableList()
        val hit = want.count { got.remove(it) }
        return if (want.isEmpty()) 0 else hit * 100 / want.size
    }

    private fun scrub(text: String, key: String): String {
        var s = text.replace(key, "<key>")
        // Keys quoted back in part, as OpenAI does ("sk-proj-…abcd").
        if (key.length > 12) s = s.replace(key.take(10), "<key>").replace(key.takeLast(6), "<key>")
        return s.replace(Regex("(sk|gsk|xai|sk-or|sk-ant)-[A-Za-z0-9_*\\-]{6,}"), "<key>").take(400)
    }

    private fun write() {
        val json = Json { prettyPrint = true }
        File(out, "results.json").writeText(json.encodeToString(rows.toList()))
        val md = StringBuilder()
        md.appendLine("# Provider check ${LocalDate.now()}")
        md.appendLine()
        val counts = rows.groupingBy { it.outcome }.eachCount()
        md.appendLine("Rows: ${rows.size} · " + counts.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} ${it.value}" })
        md.appendLine()
        md.appendLine("## Results")
        for ((provider, list) in rows.groupBy { it.provider }) {
            md.appendLine()
            md.appendLine("### $provider")
            md.appendLine()
            md.appendLine("| part | model | probe | outcome | ms | detail |")
            md.appendLine("|---|---|---|---|---|---|")
            list.forEach { r ->
                md.appendLine("| ${r.part} | `${r.model}` | ${r.probe} | **${r.outcome}** | ${r.ms} | ${r.detail.replace("|", "\\|")} |")
            }
        }
        md.appendLine()
        md.appendLine("## What the code claims")
        md.appendLine()
        md.append(facts)
        File(out, "report.md").writeText(md.toString())
    }
}
