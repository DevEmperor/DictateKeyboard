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

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.text.Normalizer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Password-protected backup archives (issue #470).
 *
 * Three things have to hold. The key has to be PBKDF2 exactly, or an archive made today will not open on
 * the next phone. A protected archive has to come back byte for byte, at every size, and not at all when
 * anything about it has been changed: a wrong password, a damaged or missing piece, a header that was
 * edited. And an older Dictate has to refuse it rather than read it as an empty backup.
 *
 * Small round counts and chunk sizes keep the suite fast; one test runs the real defaults.
 */
class BackupEncryptionTest {

    private val dir: File = Files.createTempDirectory("backup-encryption").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun platformPbkdf2(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, length * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /**
     * A stand-in for a real backup: its metadata, and a preference file carrying a key. Stored without
     * compression, so the key can be found in its bytes and its absence from the protected one means something.
     */
    private fun plainArchive(name: String = "plain.zip"): File {
        val file = dir.resolve(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry(Backup.METADATA_JSON_NAME))
            zip.write("""{"package":"net.devemperor.dictate","versionCode":99,"versionName":"6.5.0","timestamp":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("jetpref_datastore/florisboard-app-prefs.jetpref"))
            zip.write("s;dictate__provider_accounts;\"{\\\"apiKey\\\":\\\"sk-the-secret-one\\\"}\"\n".toByteArray())
            zip.write(Random(7).nextBytes(5000))
            zip.closeEntry()
        }
        return file
    }

    private fun sealed(plain: ByteArray, key: ByteArray, aad: ByteArray, chunkSize: Int): ByteArray =
        ByteArrayOutputStream().also { BackupEncryption.seal(ByteArrayInputStream(plain), it, key, aad, chunkSize) }
            .toByteArray()

    private fun opened(sealed: ByteArray, key: ByteArray, aad: ByteArray, chunkSize: Int): ByteArray =
        ByteArrayOutputStream().also { BackupEncryption.open(ByteArrayInputStream(sealed), it, key, aad, chunkSize) }
            .toByteArray()

    @Test
    fun `the key is PBKDF2-HMAC-SHA256 as RFC 7914 and the platform compute it`() {
        // RFC 7914, section 11: P = "passwd", S = "salt", c = 1, dkLen = 64.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(BackupEncryption.deriveKey("passwd", "salt".toByteArray(), iterations = 1, length = 64)),
        )
        // The JVM's own PBKDF2 takes UTF-8, so it is a second witness for any password once normalised —
        // including one longer than an HMAC block, which HMAC hashes first.
        val salt = Random(1).nextBytes(16)
        for (password in listOf("correct horse", "Grüße aus Köln 🎙", "ж".repeat(70))) {
            for (iterations in listOf(1, 2, 1000)) {
                val normalised = Normalizer.normalize(password, Normalizer.Form.NFKC)
                assertContentEquals(
                    platformPbkdf2(normalised, salt, iterations, 32),
                    BackupEncryption.deriveKey(password, salt, iterations),
                    "$password / $iterations",
                )
            }
        }
    }

    @Test
    fun `the same password typed two ways gives the same key`() {
        val salt = Random(2).nextBytes(16)
        val composed = "Grüße"
        val decomposed = "Grüße"
        assertContentEquals(
            BackupEncryption.deriveKey(composed, salt, 10),
            BackupEncryption.deriveKey(decomposed, salt, 10),
        )
    }

    @Test
    fun `every size around a chunk boundary comes back byte for byte`() {
        val key = Random(3).nextBytes(32)
        val aad = "header".toByteArray()
        for (size in listOf(0, 1, 63, 64, 65, 127, 128, 129, 1000)) {
            val plain = Random(size).nextBytes(size)
            assertContentEquals(plain, opened(sealed(plain, key, aad, 64), key, aad, 64), "size $size")
        }
    }

    @Test
    fun `a damaged, dropped, moved or added chunk is refused, and not as a wrong password`() {
        val key = Random(4).nextBytes(32)
        val aad = "header".toByteArray()
        val chunk = 64
        val whole = chunk + 16
        val good = sealed(Random(5).nextBytes(chunk * 4 + 10), key, aad, chunk)
        fun chunk(i: Int) = good.copyOfRange(i * whole, minOf(good.size, (i + 1) * whole))

        val flipped = good.copyOf().also { it[2 * whole + 5] = (it[2 * whole + 5].toInt() xor 1).toByte() }
        val dropped = chunk(0) + chunk(2) + chunk(3) + chunk(4)
        val swapped = chunk(0) + chunk(2) + chunk(1) + chunk(3) + chunk(4)
        val cutAtBoundary = good.copyOf(4 * whole)
        val cutInside = good.copyOf(good.size - 3)
        val appended = good + byteArrayOf(1, 2, 3)
        for ((name, bytes) in listOf(
            "flipped" to flipped, "dropped" to dropped, "swapped" to swapped,
            "cut at a boundary" to cutAtBoundary, "cut inside the last chunk" to cutInside, "appended" to appended,
        )) {
            assertFailsWith<BackupEncryption.UnreadableArchiveException>(name) { opened(bytes, key, aad, chunk) }
        }
    }

    @Test
    fun `an archive comes back whole with its password`() {
        val plain = plainArchive()
        val protected = dir.resolve("protected.zip")
        BackupEncryption.encrypt(plain, protected, "correct horse", iterations = 1000, chunkSize = 1024)
        assertTrue(BackupEncryption.isEncrypted(protected))
        assertFalse(BackupEncryption.isEncrypted(plain))

        val restored = dir.resolve("restored.zip")
        BackupEncryption.decrypt(protected, "correct horse", restored)
        assertContentEquals(plain.readBytes(), restored.readBytes())
    }

    @Test
    fun `a wrong password says so and writes nothing`() {
        val protected = dir.resolve("protected.zip")
        BackupEncryption.encrypt(plainArchive(), protected, "correct horse", iterations = 1000, chunkSize = 1024)
        val restored = dir.resolve("restored.zip")
        assertFailsWith<BackupEncryption.WrongPasswordException> {
            BackupEncryption.decrypt(protected, "correct hose", restored)
        }
        assertFalse(restored.exists())
    }

    @Test
    fun `an older version finds no metadata and refuses the archive, and the key is not in it`() {
        // Every version before #470 opens a backup by reading backup_metadata.json and stops with
        // "Invalid archive" when it is missing. Its presence would have meant an empty restore instead.
        val plain = plainArchive()
        val protected = dir.resolve("protected.zip")
        BackupEncryption.encrypt(plain, protected, "correct horse", iterations = 1000, chunkSize = 1024)
        val names = ZipFile(protected).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals(listOf(BackupEncryption.HEADER_FILE_NAME, BackupEncryption.PAYLOAD_FILE_NAME), names)
        assertTrue("sk-the-secret-one" in plain.readBytes().decodeToString())
        assertFalse("sk-the-secret-one" in protected.readBytes().decodeToString())
    }

    @Test
    fun `an edited header does not open`() {
        val protected = dir.resolve("protected.zip")
        BackupEncryption.encrypt(plainArchive(), protected, "correct horse", iterations = 1000, chunkSize = 1024)
        val (header, payload) = ZipFile(protected).use { zip ->
            zip.getInputStream(zip.getEntry(BackupEncryption.HEADER_FILE_NAME)).readBytes() to
                zip.getInputStream(zip.getEntry(BackupEncryption.PAYLOAD_FILE_NAME)).readBytes()
        }
        // Still valid JSON with the same values, so only the check over the header itself can catch it.
        val edited = dir.resolve("edited.zip")
        writeArchive(edited, (header.decodeToString() + " ").toByteArray(), payload)
        assertFailsWith<BackupEncryption.WrongPasswordException> {
            BackupEncryption.decrypt(edited, "correct horse", dir.resolve("restored.zip"))
        }
    }

    @Test
    fun `an archive from a newer format is unreadable, not a wrong password`() {
        val newer = dir.resolve("newer.zip")
        val header = Json.encodeToString(
            BackupEncryption.Header(
                version = 2,
                kdf = "Argon2id",
                iterations = 3,
                salt = "AAAAAAAAAAAAAAAAAAAAAA==",
                cipher = "AES-256-GCM",
                chunkSize = 1024,
            ),
        )
        writeArchive(newer, header.toByteArray(), ByteArray(100))
        assertFailsWith<BackupEncryption.UnreadableArchiveException> {
            BackupEncryption.decrypt(newer, "correct horse", dir.resolve("restored.zip"))
        }
    }

    @Test
    fun `the defaults are the ones the format promises`() {
        val plain = plainArchive()
        val protected = dir.resolve("protected.zip")
        BackupEncryption.encrypt(plain, protected, "correct horse")
        val header = ZipFile(protected).use { zip ->
            zip.getInputStream(zip.getEntry(BackupEncryption.HEADER_FILE_NAME)).readBytes().decodeToString()
        }
        val parsed = Json.decodeFromString<BackupEncryption.Header>(header)
        assertEquals(600_000, parsed.iterations)
        assertEquals(1 shl 20, parsed.chunkSize)
        assertEquals("PBKDF2-HMAC-SHA256", parsed.kdf)
        assertEquals("AES-256-GCM", parsed.cipher)

        val restored = dir.resolve("restored.zip")
        BackupEncryption.decrypt(protected, "correct horse", restored)
        assertContentEquals(plain.readBytes(), restored.readBytes())
    }

    private fun writeArchive(target: File, header: ByteArray, payload: ByteArray) {
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEncryption.HEADER_FILE_NAME))
            zip.write(header)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupEncryption.PAYLOAD_FILE_NAME))
            zip.write(payload)
            zip.closeEntry()
        }
    }
}
