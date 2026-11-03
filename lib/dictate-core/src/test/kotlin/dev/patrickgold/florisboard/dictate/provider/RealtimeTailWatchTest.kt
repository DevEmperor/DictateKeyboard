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

import dev.patrickgold.florisboard.dictate.provider.RealtimeTailWatch.Companion.ANSWER_WAIT_MS
import dev.patrickgold.florisboard.dictate.provider.RealtimeTailWatch.Companion.CAP_MS
import dev.patrickgold.florisboard.dictate.provider.RealtimeTailWatch.Companion.IDLE_MS
import dev.patrickgold.florisboard.dictate.provider.RealtimeTailWatch.Companion.IN_FLIGHT_BYTES
import dev.patrickgold.florisboard.dictate.provider.RealtimeTailWatch.Companion.STALL_MS
import dev.patrickgold.florisboard.dictate.provider.RealtimeTailWatch.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stop rules of #372 and #447, read off a clock in milliseconds that starts at the stop. */
class RealtimeTailWatchTest {

    private val stop = 100_000L

    /** Feeds [watch] a reading every 50 ms until it answers, as the controller does; returns when and what. */
    private fun run(
        watch: RealtimeTailWatch,
        until: Long = 200_000L,
        pending: (Long) -> Long = { 0L },
        lastText: (Long) -> Long = { stop },
        answeredAt: Long = Long.MAX_VALUE,
    ): Pair<Long, Outcome?> {
        var t = 0L
        while (t <= until) {
            watch.check(stop + t, pending(t), lastText(t), t >= answeredAt)?.let { return t to it }
            t += 50
        }
        return t to null
    }

    // --- The connection kept up: the rules of #372, unchanged ---------------------------------------

    @Test
    fun aLinkThatKeptUpEndsOnSilence() {
        val watch = RealtimeTailWatch(stop, pendingAtStop = 0L, endsWithAnswer = false)
        assertFalse(watch.behind)
        assertEquals(IDLE_MS to Outcome.SILENCE, run(watch))
    }

    @Test
    fun theEndMarkerStillQueuedAtTheStopIsNoBacklog() {
        // finish() has just queued the end marker and maybe a last frame: that is every stop, not a slow link.
        val watch = RealtimeTailWatch(stop, pendingAtStop = 4_000L, endsWithAnswer = false)
        assertFalse(watch.behind)
        assertEquals(IDLE_MS to Outcome.SILENCE, run(watch))
    }

    @Test
    fun aSessionThatAnswersIsGivenLongerToDoSo() {
        val watch = RealtimeTailWatch(stop, pendingAtStop = 0L, endsWithAnswer = true)
        assertEquals(ANSWER_WAIT_MS to Outcome.SILENCE, run(watch))
    }

    @Test
    fun oneThatAnswersOnlySpeechIsNotWaitedForAfterAPause() {
        // Gemini after a 3.5 s pause before the stop: no turn left to end, so no answer comes.
        val watch = RealtimeTailWatch(stop, pendingAtStop = 0L, endsWithAnswer = true, answersAfterPause = false)
        assertEquals(IDLE_MS to Outcome.SILENCE, run(watch))
    }

    @Test
    fun behindItsAnswerIsWaitedForAllTheSame() {
        // The backlog is speech the provider has not heard yet: there is a turn to end, and an answer to come.
        val watch = RealtimeTailWatch(stop, draining(0), endsWithAnswer = true, answersAfterPause = false)
        val (at, outcome) = run(watch, pending = ::draining)
        assertEquals(10_000L + 1_000L + ANSWER_WAIT_MS to Outcome.SILENCE, at to outcome)
    }

    @Test
    fun aDropoutAtTheStopIsWaitedOutWhenTheAnswerIsStillToCome() {
        // AssemblyAI with the uplink stalled for 2 s at the stop (2026-11-02): no text for 2.5 s, then the
        // rest and Termination. The 1.2 s window committed the dictation without its last words.
        val watch = RealtimeTailWatch(stop, pendingAtStop = 0L, endsWithAnswer = true)
        val (at, outcome) = run(
            watch,
            lastText = { t -> stop + if (t >= 2_500L) 2_500L else 0L },
            answeredAt = 2_600L,
        )
        assertEquals(2_600L to Outcome.PROVIDER, at to outcome)
    }

    @Test
    fun theProvidersAnswerEndsTheWaitAtOnce() {
        val watch = RealtimeTailWatch(stop, pendingAtStop = 0L, endsWithAnswer = true)
        assertEquals(300L to Outcome.PROVIDER, run(watch, answeredAt = 300L))
    }

    @Test
    fun textStillArrivingAtTheCapMeansTheStreamWasCut() {
        val watch = RealtimeTailWatch(stop, pendingAtStop = 0L, endsWithAnswer = true)
        val (at, outcome) = run(watch, lastText = { t -> stop + t })
        assertEquals(CAP_MS, at)
        assertEquals(Outcome.CAP, outcome)
        assertFalse(outcome!!.complete)
    }

    // --- The uplink fell behind (#447) -------------------------------------------------------------

    /** 640 KiB at the stop, draining at 64 KiB/s: gone after 10 s. */
    private fun draining(t: Long): Long = (640L * 1024 - 64L * 1024 * t / 1000).coerceAtLeast(0L)

    @Test
    fun aPauseStillOnThePhoneIsNotTheEnd() {
        // The Soniox run that committed 70 % of its text: two seconds of no text right after the stop,
        // because the pause the provider was transcribing had not even left the phone.
        val watch = RealtimeTailWatch(stop, pendingAtStop = draining(0), endsWithAnswer = true)
        assertTrue(watch.behind)
        val (at, outcome) = run(watch, pending = ::draining, answeredAt = 11_000L)
        assertEquals(11_000L to Outcome.PROVIDER, at to outcome)
    }

    @Test
    fun aSlowStreamIsNeverCutForStillDelivering() {
        // Text keeps arriving well past the old 8 s cap: that is the backlog being transcribed, not a fault.
        val watch = RealtimeTailWatch(stop, pendingAtStop = draining(0), endsWithAnswer = true)
        val (at, outcome) = run(watch, pending = ::draining, lastText = { t -> stop + t }, answeredAt = 12_500L)
        assertEquals(12_500L to Outcome.PROVIDER, at to outcome)
    }

    @Test
    fun afterTheLastByteLeftTheSendBufferStillGetsItsTime() {
        val watch = RealtimeTailWatch(stop, pendingAtStop = draining(0), endsWithAnswer = true)
        val (at, outcome) = run(watch, pending = ::draining)
        // 64 KiB in flight at the 64 KiB/s the queue drained at: one second. Then an answer that is certain
        // to come is waited for as long as a dead connection would be.
        assertEquals(1_000L, watch.inFlightMs)
        assertEquals(10_000L, watch.drainedAt - stop)
        assertEquals(10_000L + 1_000L + STALL_MS, at)
        assertEquals(Outcome.SILENCE, outcome)
    }

    @Test
    fun withoutAnAnswerSilenceAfterTheDrainEndsIt() {
        // ElevenLabs: nothing answers the end, so silence decides once everything has left and arrived.
        val watch = RealtimeTailWatch(stop, pendingAtStop = draining(0), endsWithAnswer = false)
        val (at, outcome) = run(watch, pending = ::draining, lastText = { t -> stop + minOf(t, 10_500L) })
        assertEquals(10_500L + 1_000L + IDLE_MS, at)
        assertEquals(Outcome.SILENCE, outcome)
    }

    @Test
    fun aBacklogThatStopsMovingIsADeadConnection() {
        val watch = RealtimeTailWatch(stop, pendingAtStop = 300_000L, endsWithAnswer = true)
        // Moves for two seconds, then nothing leaves and nothing comes back.
        val (at, outcome) = run(watch, pending = { t -> 300_000L - 10L * minOf(t, 2_000L) })
        assertEquals(2_000L + STALL_MS, at)
        assertEquals(Outcome.STALLED, outcome)
        assertFalse(outcome!!.complete)
    }

    @Test
    fun textComingBackKeepsAStuckLookingQueueAlive() {
        // The queue can sit still while one big frame goes out; text from the provider is progress too.
        val watch = RealtimeTailWatch(stop, pendingAtStop = 300_000L, endsWithAnswer = true)
        val (at, outcome) = run(
            watch,
            pending = { t -> if (t < 15_000L) 300_000L else 0L },
            lastText = { t -> stop + t },
            answeredAt = 16_000L,
        )
        assertEquals(16_000L to Outcome.PROVIDER, at to outcome)
    }

    @Test
    fun aCrawlingDrainBoundsTheTimeInFlight() {
        // 20 KiB over 60 s is about 2.7 kbit/s: 64 KiB of it would take minutes, so the estimate is capped.
        val watch = RealtimeTailWatch(stop, pendingAtStop = 20L * 1024, endsWithAnswer = true)
        run(watch, pending = { t -> if (t < 60_000L) 20L * 1024 - t / 3 else 0L })
        assertEquals(RealtimeTailWatch.MAX_IN_FLIGHT_MS, watch.inFlightMs)
    }

    @Test
    fun noStreamIsWaitedForForever() {
        val watch = RealtimeTailWatch(stop, pendingAtStop = 10_000_000L, endsWithAnswer = true)
        val (at, outcome) = run(watch, until = 200_000L, pending = { t -> 10_000_000L - t }, lastText = { t -> stop + t })
        assertEquals(RealtimeTailWatch.HARD_CAP_MS to Outcome.CAP, at to outcome)
    }

    @Test
    fun theInFlightAllowanceIsTheDoubledSendBuffer() {
        assertEquals(2L * RealtimeClient.SEND_BUFFER_BYTES, IN_FLIGHT_BYTES)
        assertNull(RealtimeTailWatch(stop, 0L, true).check(stop, 0L, stop, false))
    }
}
