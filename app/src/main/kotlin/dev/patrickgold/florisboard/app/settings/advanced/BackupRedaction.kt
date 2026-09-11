/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.advanced

import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.hasNoSecrets
import dev.patrickgold.florisboard.dictate.provider.withSecretsFrom
import dev.patrickgold.florisboard.dictate.provider.withoutSecrets

/**
 * Taking the credentials out of an exported preference store, and putting them back in on the way in
 * (issue #367).
 *
 * A backup's "Preferences" component is the whole JetPref store, so it carried every API key in plain
 * text — and the backup screen offers a share menu, which made the leaking flow the one the UI proposed.
 * These functions are what "without provider credentials" means in practice.
 *
 * They work on the **whole document as a string**, because that is the shape JetPref hands over: its
 * `DataStore.persist` builds the entire file in one `StringBuilder` and calls `DataStoreWriter.write` with
 * it exactly once, and `DataStoreReader.read` is the mirror image. Wrapping those two interfaces (see
 * `BackupScreen`/`RestoreScreen`) means the keys are gone before anything touches the disk — a file
 * rewritten after the fact would have existed unredacted in the cache directory first, however briefly.
 *
 * The format, for the record, because nothing in the library documents it: one line per preference that
 * actually has a value, `<typeId>;<key>;<rawEncodedValue>`, terminated with `\n`. Type ids are `b d f i l
 * s`; a `custom` or `enum` preference is a string one, so the keyring is an `s` line whose value is JSON
 * wrapped in quotes and escaped (see [encodeValue]). That escaping is why a value never contains a raw
 * newline, and why splitting a line at the first two delimiters is safe.
 */
object BackupRedaction {
    /** The JetPref line delimiter. Private in the library, so it is restated here. */
    private const val DELIMITER = ";"

    /**
     * The provider keyring (`AppPrefs.dictate.providerAccounts`) — the one secret that is only *partly* a
     * secret, so its line is rewritten rather than dropped.
     */
    const val ACCOUNTS_KEY = "dictate__provider_accounts"

    /**
     * Preferences whose entire value is a credential, so the whole line goes.
     *
     * The proxy pair and the bring-your-own KLIPY key are as much a credential as an API key; the two
     * `dictate__*api_key` entries are the deprecated flat prefs kept as a migration source, which are still
     * set on any install that came up from the legacy app. None of them has a non-secret half worth
     * keeping, and an absent line is a no-op on a Merge restore.
     */
    val DROPPED_KEYS = setOf(
        "dictate__proxy_username",
        "dictate__proxy_password",
        "gif__klipy_api_key",
        "dictate__api_key",
        "dictate__rewording_api_key",
        // Not credentials, but they belong to the credential that is leaving. The nudge latch is keyed to
        // a Cloud account, and `DictateCloud.forget()` resets it for exactly this reason — the next account
        // must not inherit a low-credit warning it never earned. The KLIPY customer id is a stable
        // per-install identifier sent to the provider; two devices sharing one would fuse two people's
        // history there, and it is regenerated on first use, so dropping it costs nothing.
        "dictate__cloud_low_credit_nudged",
        "gif__customer_id",
    )

    /**
     * [document] with every credential removed: the [DROPPED_KEYS] lines gone and the keyring's own secrets
     * blanked by [withoutSecrets], everything else byte-identical.
     *
     * An unreadable keyring line is dropped, never rewritten — see [mapAccounts].
     */
    fun redact(document: String): String = rewrite(document) { line, type, key, value ->
        when {
            key in DROPPED_KEYS -> null
            key == ACCOUNTS_KEY -> mapAccounts(type, key, value) { it.withoutSecrets() }
            else -> line
        }
    }

    /**
     * [document] with the secrets of [local] filled into any gap the keyring leaves — the repair that stops
     * a credential-free archive from blanking the keys already on the phone when it is merged in.
     *
     * Only the keyring is touched. The dropped keys are *absent* from a redacted archive rather than empty,
     * which a Merge import already handles correctly by leaving the device's value alone.
     */
    fun restoreSecrets(document: String, local: ProviderAccounts): String =
        rewrite(document) { line, type, key, value ->
            if (key == ACCOUNTS_KEY) mapAccounts(type, key, value) { it.withSecretsFrom(local) } else line
        }

    /**
     * The keyring carried by [document], or null when it has no keyring line or an unreadable one.
     *
     * What the restore screen asks (via [hasNoSecrets]) so it can say up front that this archive will not
     * bring any keys back.
     */
    fun accountsFrom(document: String): ProviderAccounts? {
        for (line in document.lineSequence()) {
            val (_, key, value) = split(line) ?: continue
            if (key == ACCOUNTS_KEY) {
                return ProviderAccounts.Serializer.deserializeOrNull(decodeValue(value))
            }
        }
        return null
    }

    /**
     * Walks [document] line by line, handing each parsed line to [transform] — which returns the line to
     * write, or null to drop it — and passes anything unparseable through untouched (JetPref skips such a
     * line on import anyway, so inventing a reading of it would only lose information).
     *
     * `lines()`/`joinToString` is a faithful round trip: the document ends in `\n`, which `lines()` renders
     * as a trailing empty element and the join puts back.
     */
    private inline fun rewrite(
        document: String,
        transform: (line: String, type: String, key: String, value: String) -> String?,
    ): String = document.lines()
        .mapNotNull { line ->
            val (type, key, value) = split(line) ?: return@mapNotNull line
            transform(line, type, key, value)
        }
        .joinToString("\n")

    /**
     * JetPref's own string encoding, reimplemented because `StringEncoder` is `internal` to the library
     * (jetpref 0.3.0) and cannot be called from here.
     *
     * Copied operation for operation, including the order of the replacements, because the point is to be
     * *transparent*: a keyring line this pass rewrites has to come out exactly as the library would have
     * written it, and be read back exactly as the library reads it. Where the library's escaping is lossy
     * — a literal backslash-n in a value decodes to a newline — this is lossy in the same place, which is
     * the only behaviour that keeps a redacted archive indistinguishable from an untouched one. Do not
     * "improve" it; fix it in jetpref or not at all.
     */
    internal fun encodeValue(value: String): String = buildString {
        append('"')
        append(
            value
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\"", "\\\"")
        )
        append('"')
    }

    /** The inverse of [encodeValue]; the empty string for anything not in quotes, as the library does. */
    internal fun decodeValue(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"") || trimmed.length < 2) return ""
        return trimmed.substring(1, trimmed.length - 1)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\\\", "\\")
    }

    /** The type id, key and raw encoded value of a JetPref line, or null if it is not one. */
    private fun split(line: String): Triple<String, String, String>? {
        val parts = line.split(DELIMITER, limit = 3)
        return if (parts.size < 3) null else Triple(parts[0], parts[1], parts[2])
    }

    /**
     * Rebuilds the keyring line with [block] applied to it, or drops the line if the value cannot be read.
     *
     * Dropping is the important half. `ProviderAccounts.Serializer.deserialize` answers an unreadable blob
     * with an empty keyring, and writing *that* back would turn a line we merely failed to parse into a
     * deliberate-looking instruction to forget every key in it. An absent line asks the importer to keep
     * what the device already has, which is the only safe reading of "we could not tell what this was".
     */
    private inline fun mapAccounts(
        type: String,
        key: String,
        value: String,
        block: (ProviderAccounts) -> ProviderAccounts,
    ): String? {
        val accounts = ProviderAccounts.Serializer.deserializeOrNull(decodeValue(value))
            ?: return null
        val encoded = encodeValue(ProviderAccounts.Serializer.serialize(block(accounts)))
        // The parsed type id is re-emitted rather than assumed. A `custom` preference is a string one
        // today, so it is always "s" — but the original is right here, and a line that disagrees with
        // itself would be dropped by the importer without a word.
        return "$type$DELIMITER$key$DELIMITER$encoded"
    }
}
