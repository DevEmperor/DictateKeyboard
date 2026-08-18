/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The "cheaper per minute" badge on the credit packs.
 *
 * The figure is deliberately computed from Play's own amounts rather than from a table in the app,
 * because Play's per-country price list is not a conversion of the euro one. These tests therefore
 * feed in amounts the way Play hands them over — micros, with a currency beside them — and pin the
 * badge each pack earns at the current list price, so a mistyped price shows up here instead of in
 * a screenshot from a customer.
 */
class CloudSavingsTest {

    private fun offer(pack: DictateCloudPack, euro: Double, currency: String = "EUR") =
        DictateCloud.Offer(
            pack = pack,
            title = pack.name,
            description = "",
            formattedPrice = "$euro $currency",
            priceMicros = Math.round(euro * 1_000_000),
            priceCurrency = currency,
        )

    /** The list as it goes live: 1.99 / 4.99 / 9.99 / 19.99 €. */
    private val notes = offer(DictateCloudPack.NOTES, 1.99)
    private val daily = offer(DictateCloudPack.DAILY, 4.99)
    private val writer = offer(DictateCloudPack.WRITER, 9.99)
    private val pro = offer(DictateCloudPack.PRO, 19.99)

    @Test
    fun `the smallest pack is the yardstick and carries no badge of its own`() {
        assertNull(savingsPercent(notes, notes))
    }

    @Test
    fun `the current price list earns the badges the pricing was chosen for`() {
        assertEquals(24, savingsPercent(notes, writer))
        assertEquals(31, savingsPercent(notes, pro))
    }

    /**
     * Daily really is only about 6 % cheaper per minute, and that is not worth printing next to
     * "31 % cheaper" — it makes the smaller pack look considered rather than the larger one look
     * good. The absence is the decision, so it is pinned rather than left to chance.
     */
    @Test
    fun `a saving too small to be worth claiming is left off`() {
        assertNull(savingsPercent(notes, daily))
    }

    @Test
    fun `two currencies are never compared`() {
        assertNull(savingsPercent(notes, offer(DictateCloudPack.PRO, 19.99, currency = "USD")))
    }

    @Test
    fun `an amount Play did not supply produces no badge`() {
        assertNull(savingsPercent(notes.copy(priceMicros = 0L), pro))
        assertNull(savingsPercent(notes, pro.copy(priceMicros = 0L)))
    }

    /** A pack that is dearer per minute must go unmarked, never carry a negative saving. */
    @Test
    fun `a pack that is not actually cheaper gets nothing`() {
        assertNull(savingsPercent(notes, offer(DictateCloudPack.PRO, 40.00)))
        assertNull(savingsPercent(notes, offer(DictateCloudPack.PRO, 29.19)))
    }

    /**
     * Rounded down, always. Writer's true figure is 24.7 %, and 25 would be a claim the price list
     * does not support — small, but it is the direction in which an advertised number must never
     * err.
     */
    @Test
    fun `the percentage is rounded down, not to the nearest`() {
        assertEquals(24, savingsPercent(notes, writer))
        // Just over the threshold counts (10.5 %), just under it does not (9.7 %). Deliberately
        // not tested exactly on 10.000 %: that boundary is decided by the last bit of a double,
        // and a test that pins it would be pinning the arithmetic rather than the rule.
        assertEquals(10, savingsPercent(notes, offer(DictateCloudPack.DAILY, 4.75)))
        assertNull(savingsPercent(notes, offer(DictateCloudPack.DAILY, 4.79)))
    }
}
