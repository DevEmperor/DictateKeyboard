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

    /** Rewordings the pack includes. Mirrors `REWORDS_PER_MINUTE` on the server. */
    val rewords: Int get() = minutes * REWORDS_PER_MINUTE

    companion object {
        /**
         * How many rewordings come with each purchased minute — the server's figure, repeated here
         * only to state it in the shop. What is actually granted is decided there; a wrong number
         * here shows a wrong promise, never a wrong balance.
         */
        const val REWORDS_PER_MINUTE = 5

        /** Display order in the shop — cheapest first, as in the Play Console. */
        val ordered: List<DictateCloudPack> = listOf(NOTES, DAILY, WRITER, PRO)

        val productIds: List<String> = ordered.map { it.productId }

        fun byProductId(productId: String): DictateCloudPack? =
            entries.firstOrNull { it.productId == productId }
    }
}
