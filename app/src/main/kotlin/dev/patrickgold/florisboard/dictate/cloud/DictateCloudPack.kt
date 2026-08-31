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

/**
 * The four credit packs, mirroring the one-time products in the Play Console.
 *
 * The ids must match those products character for character — and the server's `PACKAGES` table as
 * well, since that is what turns a purchase into minutes. Play knows the name, the description and
 * the local price of each product but not what it is worth in dictation, so the minutes live here
 * for the store view; after a purchase the balance always comes from the server, never from this.
 *
 * Deliberately an enum and not a server-fetched list: the app must be able to show what is on offer
 * before it has ever spoken to either Google or the wallet service, including with no network at
 * all. A wrong number here shows a wrong figure in the shop; it can never grant wrong credit.
 */
enum class DictateCloudPack(val productId: String, val minutes: Int) {
    NOTES("credits_notes", 150),
    DAILY("credits_daily", 400),
    WRITER("credits_writer", 1000),
    PRO("credits_pro", 2200);

    /**
     * Roughly how many rewordings the pack is worth if it is spent on nothing else.
     *
     * Not an allowance — there is no second balance any more. Credit is seconds, and every service
     * is priced into them, which is what makes a pack's price a hard ceiling on what it can cost to
     * serve. A rewording of ordinary length is worth about two seconds, so this is a division and
     * says the same thing as the minutes beside it.
     */
    val rewords: Int get() = roundDown((minutes * 60) / SECONDS_PER_REWORD)

    companion object {
        /**
         * What a rewording of ordinary length costs, in seconds of credit — the server's figure
         * (`TYPICAL_REWORD_SECONDS`), repeated here only to say it in the shop before the app has
         * ever spoken to the service. A wrong number here shows a wrong estimate, never a wrong
         * balance: what is charged is decided where the money is.
         *
         * It has moved twice, and both times for a reason worth knowing. It read 2 by assumption;
         * the server measured 131 real rewordings — 327 tokens in, 63 out — and came down to 1, and
         * this copy stayed behind, so the shop promised 4,500 where the service granted 9,000. Then
         * the conversion itself changed: credit is billed at what a second *costs* to serve rather
         * than at what it sells for, which makes every service earn the same margin and puts an
         * ordinary rewording at seven seconds.
         *
         * **When the server's figure moves, this moves in the same commit.** Nothing notices on its
         * own — a wrong number here shows a wrong estimate, never a wrong balance.
         */
        const val SECONDS_PER_REWORD = 7

        /**
         * Rounds an "about this many" figure **down** to something a person can read.
         *
         * The division leaves numbers like 1,285 and 18,857, and a figure that precise reads as a
         * promise rather than an estimate — while being neither: the real count depends on how long
         * each rewording turns out to be. Rounding down keeps it honest in the one direction that
         * matters, since a pack that yields more than it said is a good surprise and one that yields
         * less is a complaint.
         *
         * The step grows with the number, because 50 is a sensible unit at a thousand and a silly one
         * at twenty thousand: 1,285 → 1,250, 3,428 → 3,400, 8,571 → 8,500, 18,857 → 18,500.
         */
        fun roundDown(value: Int): Int {
            val step = when {
                value < 200 -> 10
                value < 2_000 -> 50
                value < 10_000 -> 100
                else -> 500
            }
            return (value / step) * step
        }

        /** Display order in the shop — cheapest first, as in the Play Console. */
        val ordered: List<DictateCloudPack> = listOf(NOTES, DAILY, WRITER, PRO)

        val productIds: List<String> = ordered.map { it.productId }

        fun byProductId(productId: String): DictateCloudPack? =
            entries.firstOrNull { it.productId == productId }
    }
}
