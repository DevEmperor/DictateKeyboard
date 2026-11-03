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
 * Decides when a stopped realtime dictation has everything it is going to get (#372, #447). The caller
 * feeds it a reading every few dozen milliseconds; it answers once the wait is over, and how it ended.
 *
 * The stop is not the end of the stream: the provider is still transcribing whatever it has not caught up
 * with. How far behind it is depends on the uplink, and the two cases need opposite rules.
 *
 * **The connection kept up** — nothing worth speaking of waits on the phone at the stop. Then the rule of
 * #372 holds, which every measurement on a good link since has borne out: the session's own end answer ends
 * the wait at once, every piece of text renews an [IDLE_MS] window of silence, and [CAP_MS] bounds the whole
 * wait. Text still arriving at the cap means the stream is behind for reasons of its own, and the recording
 * goes through the batch path instead.
 *
 * A session whose answer comes after every stop ([RealtimeSession.answersAfterPause]) gets [ANSWER_WAIT_MS]
 * of silence instead of [IDLE_MS], because "kept up" is only what the phone can see. A middlebox that
 * acknowledges the bytes itself (a filtering VPN, the emulator's NAT) holds the backlog where no reading
 * reaches it, and then a pause in that backlog looked like the end: AssemblyAI and Deepgram at 200 kbit/s
 * committed 69 % of a 13 s dictation, and a 2 s dropout right at the stop could cost the last words. With the
 * longer window both landed whole, and Soniox, OpenAI, AssemblyAI and Deepgram waited out the dropout
 * (2026-11-02).
 * On a link that really kept up the answer arrives in 0.1–1.2 s, a pause before the stop included, and the
 * window is never reached. Gemini is left out: it answers a turn of speech, and after a pause there is none.
 *
 * **Audio is still waiting to leave the phone** — the uplink was slower than the stream. Here both of those
 * rules are wrong (measured in #447). Silence in the text means nothing while the provider has not received
 * the pause yet: a 1.2 s window committed 70 % of a 28 s Soniox dictation at 200 kbit/s, with no error. And
 * text still arriving after 8 s is not a broken stream but a slow one; replacing it with the whole WAV over
 * the same link took 30–60 s, and on Gemini ended in an empty field. So the wait follows the upload: as long
 * as bytes leave the phone or text comes back the stream is alive, and only [STALL_MS] without either is a
 * dead connection. Once the last byte has left, what is still on its way is at most the socket's send buffer
 * ([RealtimeClient.SEND_BUFFER_BYTES]), and the rate the backlog drained at says how long that takes.
 * After that, a session whose answer comes after every stop is waited for as long as a dead connection
 * would be, [STALL_MS]: while the link lives its answer is certain, and giving up early costs a re-upload
 * over the same slow link. With [ANSWER_WAIT_MS] there, OpenAI's answer to 28 s at 150 kbit/s had not come
 * and the recording went out again, 78 s instead of 54 s (2026-11-02; that emulator held ~200 KB beyond the
 * send buffer, a phone holds nothing beyond it). Gemini, whose answer is not certain, gets [ANSWER_WAIT_MS];
 * a session without one ends on [IDLE_MS] of silence.
 *
 * Plain arithmetic on the readings it is given, so it is tested without a clock or a socket.
 */
class RealtimeTailWatch(
    private val stoppedAt: Long,
    /** [RealtimeSession.pendingUploadBytes] right after the stop was sent. */
    val pendingAtStop: Long,
    /** [RealtimeSession.endsWithAnswer]. */
    private val endsWithAnswer: Boolean,
    /** [RealtimeSession.answersAfterPause]. */
    private val answersAfterPause: Boolean = endsWithAnswer,
) {

    enum class Outcome(
        /** Whether the transcript can be trusted to be whole. */
        val complete: Boolean,
    ) {
        /** The session closed itself: its provider answered the end of the stream. */
        PROVIDER(true),

        /** Everything had arrived and the provider went quiet. */
        SILENCE(true),

        /** Audio was still waiting to leave the phone and nothing moved for [STALL_MS]. */
        STALLED(false),

        /** The wait ran out while text was still arriving. */
        CAP(false),
    }

    /** Whether the upload was behind at the stop, which decides the rules. */
    val behind: Boolean = pendingAtStop >= BACKLOG_BYTES

    /** When the last byte left the phone, or -1 while it has not. */
    var drainedAt: Long = -1L
        private set

    /** How long the bytes still in flight at [drainedAt] take, at the rate the backlog drained at. */
    var inFlightMs: Long = 0L
        private set

    private var lastPending = pendingAtStop
    private var movedAt = stoppedAt

    /**
     * One reading: [now], what is still waiting to leave ([pendingBytes]), when text last arrived
     * ([lastTextAt], the stop itself when none has since) and whether the session has closed itself
     * ([answered]). Returns how the wait ended, or null to keep waiting.
     */
    fun check(now: Long, pendingBytes: Long, lastTextAt: Long, answered: Boolean): Outcome? {
        if (answered) return Outcome.PROVIDER
        val textAt = maxOf(stoppedAt, lastTextAt)
        return if (behind) behindCheck(now, pendingBytes, textAt) else keptUpCheck(now, textAt)
    }

    private fun keptUpCheck(now: Long, textAt: Long): Outcome? = when {
        now - textAt >= if (answersAfterPause) ANSWER_WAIT_MS else IDLE_MS -> Outcome.SILENCE
        now - stoppedAt >= CAP_MS -> Outcome.CAP
        else -> null
    }

    private fun behindCheck(now: Long, pendingBytes: Long, textAt: Long): Outcome? {
        if (pendingBytes < lastPending) {
            lastPending = pendingBytes
            movedAt = now
        }
        if (now - stoppedAt >= HARD_CAP_MS) return Outcome.CAP
        if (pendingBytes > 0L) {
            return if (now - maxOf(movedAt, textAt) >= STALL_MS) Outcome.STALLED else null
        }
        if (drainedAt < 0L) {
            drainedAt = now
            // pendingAtStop bytes left in (now - stoppedAt) ms; the send buffer drains at the same rate.
            val drainMs = (now - stoppedAt).coerceAtLeast(1L)
            inFlightMs = (IN_FLIGHT_BYTES * drainMs / pendingAtStop).coerceAtMost(MAX_IN_FLIGHT_MS)
        }
        val quietMs = inFlightMs + when {
            answersAfterPause -> STALL_MS
            endsWithAnswer -> ANSWER_WAIT_MS
            else -> IDLE_MS
        }
        return if (now - maxOf(drainedAt, textAt) >= quietMs) Outcome.SILENCE else null
    }

    companion object {
        /** Silence that ends the wait once everything has arrived (#372). */
        const val IDLE_MS = 1_200L

        /** The whole wait when the connection kept up (#372). */
        const val CAP_MS = 8_000L

        /**
         * Less than this waiting at the stop is the connection keeping up: the end marker itself and the
         * last frame or two are always still queued the moment the stop is sent. 16 KiB is about half a
         * second of the slowest stream here (16 kHz PCM, sent as binary).
         */
        const val BACKLOG_BYTES = 16L * 1024

        /** No byte left and no text came back for this long: the connection is gone (#447). */
        const val STALL_MS = 10_000L

        /**
         * Silence an answer is waited out for where it may not come (#447): a certain one on a link that
         * looked as if it kept up, Gemini's once everything has left.
         */
        const val ANSWER_WAIT_MS = 4_000L

        /** What can be in flight after the queue drained: the send buffer, doubled by the kernel. */
        const val IN_FLIGHT_BYTES = 2L * RealtimeClient.SEND_BUFFER_BYTES

        /** A bound on [inFlightMs] for a backlog that drained at a crawl. */
        const val MAX_IN_FLIGHT_MS = 10_000L

        /** No stream is waited for longer than this, however much it still moves. */
        const val HARD_CAP_MS = 120_000L
    }
}
