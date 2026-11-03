/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app

import dev.patrickgold.florisboard.app.settings.advanced.BackupRedaction
import dev.patrickgold.florisboard.dictate.data.mappings.DictateMappings
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.runtime.DataStoreReader
import dev.patrickgold.jetpref.datastore.runtime.DataStoreWriter
import dev.patrickgold.jetpref.datastore.runtime.LoadStrategy
import dev.patrickgold.jetpref.datastore.runtime.PersistStrategy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JSON preferences survive the settings file (#463).
 *
 * Everything goes through the real preference model and JetPref's own load and persist, because the bug
 * lives in JetPref's reader: a keyring with one line break in it loaded "successfully" as an empty one.
 * The texts are the ones that reader gets wrong — line breaks, and a backslash in front of `n`, `r` or a
 * quote — plus the neighbours a fix could break on the way.
 */
class PreferenceJsonTest {

    private class Memory(var content: String = "") : DataStoreReader, DataStoreWriter {
        override suspend fun read() = content
        override suspend fun write(content: String) {
            this.content = content
        }
    }

    private val texts = listOf(
        "two\nlines",
        "windows\r\nline",
        "typed \\n, not a line break",
        "C:\\new\\road",
        "ends in a backslash\\",
        "a \"quoted\" word",
        "backslash before a quote \\\"",
        "tab\there",
        "typed \\u000a",
        "semi;colon, Grüße 🎙",
    )

    private fun accountsWith(text: String) = ProviderAccounts(
        mapOf(
            "openai" to ProviderAccount(providerId = "openai", apiKey = "sk-unchanged"),
            "custom:a" to ProviderAccount(
                providerId = "custom:a",
                displayName = text,
                apiKey = text,
                customBaseUrl = text,
                chatModel = text,
                cachedModels = listOf(text, "plain"),
            ),
        ),
    )

    private fun mappingsWith(text: String) = DictateMappings(listOf(DictateMappings.Mapping(from = text, to = text)))

    private fun load(storage: Memory): FlorisPreferenceModel = runBlocking {
        val store = jetprefDataStoreOf(FlorisPreferenceModel::class)
        store.init(LoadStrategy.UseReader(storage), PersistStrategy.UseWriter(storage)).getOrThrow()
        val prefs by store
        prefs
    }

    @Test
    fun `what is saved now comes back after a restart`() {
        for (text in texts) {
            val storage = Memory()
            val before = load(storage)
            runBlocking {
                before.dictate.providerAccounts.set(accountsWith(text))
                before.dictate.customMappings.set(mappingsWith(text))
            }

            val after = load(storage)
            assertEquals(accountsWith(text), after.dictate.providerAccounts.get(), "keyring with ${text.visible()}")
            assertEquals(mappingsWith(text), after.dictate.customMappings.get(), "mappings with ${text.visible()}")
        }
    }

    @Test
    fun `a keyring saved by an earlier version comes back whole`() {
        // Plain JSON with `\n` escapes, encoded the way JetPref writes a string: what 6.4.0 left on disk.
        val json = Json { encodeDefaults = true }
        for (text in texts) {
            val accounts = accountsWith(text)
            val mappings = mappingsWith(text)
            val storage = Memory(
                "s;dictate__provider_accounts;" + BackupRedaction.encodeValue(json.encodeToString(accounts)) + "\n" +
                    "s;dictate__custom_mappings;" + BackupRedaction.encodeValue(json.encodeToString(mappings)) + "\n",
            )

            val prefs = load(storage)
            assertEquals(accounts, prefs.dictate.providerAccounts.get(), "keyring with ${text.visible()}")
            assertEquals(mappings, prefs.dictate.customMappings.get(), "mappings with ${text.visible()}")
        }
    }

    @Test
    fun `an earlier version reads what is saved now`() {
        // After a downgrade, or a backup restored on an older phone: JetPref's reader, then plain JSON.
        val json = Json { ignoreUnknownKeys = true }
        for (text in texts) {
            val accounts = accountsWith(text)
            val stored = BackupRedaction.encodeValue(ProviderAccounts.Serializer.serialize(accounts))
            assertEquals(accounts, json.decodeFromString(BackupRedaction.decodeValue(stored)), text.visible())
        }
    }

    @Test
    fun `JSON without the damaged escapes is left as it is`() {
        val plain = """{"a":"x\ty","b":"\"q\"","c":"\u00fc"}"""
        assertEquals(plain, PreferenceJson.encode(plain))
        assertEquals(plain, PreferenceJson.decode(plain))
    }

    private fun String.visible() = replace("\n", "<LF>").replace("\r", "<CR>").replace("\t", "<TAB>")
}
