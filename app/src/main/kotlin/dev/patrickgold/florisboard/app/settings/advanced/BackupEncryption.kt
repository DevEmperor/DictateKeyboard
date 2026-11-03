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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backup archives (issue #470), for those who want the API keys in a backup without
 * them lying in it readable.
 *
 * **The protected archive is a ZIP with two files and no `backup_metadata.json`.** That absence is the
 * point. Every older version opens a backup by reading that file and stops with "invalid archive" when it
 * is not there — which is the refusal an old app must give. Had the metadata been left in plain view, an
 * old app would have found it valid, found nothing else it knows, and "restored" that: a no-op on Merge,
 * and on Erase the loss of every theme, layout and clipboard item it was told to replace. `Backup.Metadata`
 * itself cannot say "encrypted" either, because old versions read it strictly and reject any field they do
 * not know. Inside the payload is the plain archive, byte for byte, so once it is decrypted the restore runs
 * exactly as it does for any other backup, with every guarantee it already gives the keyring.
 *
 * **The cipher is AES-256-GCM in 1 MiB chunks**, not one GCM message over the whole archive. A backup with
 * kept recordings runs to hundreds of megabytes, and Android's GCM holds everything back until the last
 * byte has been checked, so one message would have to fit in memory twice. Each chunk is sealed on its own,
 * with a nonce made of its position and a flag for the last one (the STREAM construction `age` uses): a
 * chunk moved, dropped, or appended, or an archive cut off at a chunk boundary, fails to decrypt instead of
 * restoring part of a backup as if it were all of it. Counting nonces is safe because the key is new for
 * every archive: it comes from a fresh random salt each time.
 *
 * **The key comes from PBKDF2-HMAC-SHA256**, 600 000 rounds (OWASP's figure for it, 2023). Argon2id and
 * scrypt would be stronger, but neither is in the platform, and this is not worth a native dependency. The
 * loop is written out over `Mac` rather than taken from `SecretKeyFactory`, because the JCA leaves it to each
 * provider how a password's characters become bytes: some take UTF-8, the PKCS #5 family takes the low eight
 * bits of each one. An archive must open on whatever Android the next phone runs, so the bytes are fixed
 * here instead, UTF-8 after NFKC normalisation. NFKC is NIST's advice for passwords (SP 800-63B): the same
 * "ü" typed on two keyboards can arrive as one code point or as two, and the user cannot see which.
 *
 * The header is fed into every chunk as associated data, so the parameters it states are covered by the
 * same check as the content.
 */
object BackupEncryption {
    const val HEADER_FILE_NAME = "encrypted_backup.json"
    const val PAYLOAD_FILE_NAME = "encrypted_backup.bin"

    /** Below this, the password field asks for more. Not a strength rule, a floor (NIST SP 800-63B). */
    const val MIN_PASSWORD_LENGTH = 8

    const val DEFAULT_ITERATIONS = 600_000
    const val DEFAULT_CHUNK_SIZE = 1 shl 20

    private const val VERSION = 1
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val CIPHER = "AES-256-GCM"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC = "HmacSHA256"
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16

    // Bounds on what a header may ask for, checked before anything is allocated or derived: the header
    // is only authenticated once the first chunk is, and that needs the chunk size and the key first.
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_ITERATIONS = 10_000_000
    private val CHUNK_SIZES = 1024..(16 shl 20)

    private val HeaderJson = Json { ignoreUnknownKeys = true }

    @Serializable
    internal data class Header(
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val cipher: String,
        val chunkSize: Int,
    )

    /** The password does not open this archive. Nothing has been written. */
    class WrongPasswordException : GeneralSecurityException("Wrong password")

    /** The archive is damaged, cut short, or written in a format this version does not know. */
    class UnreadableArchiveException(message: String) : IOException(message)

    /** Whether [archive] is a password-protected one. Throws like `ZipFile` does when it is no ZIP at all. */
    fun isEncrypted(archive: File): Boolean = ZipFile(archive).use { it.getEntry(HEADER_FILE_NAME) != null }

    /** Writes [plainArchive], an ordinary backup archive, into [target] as a password-protected one. */
    fun encrypt(
        plainArchive: File,
        target: File,
        password: String,
        iterations: Int = DEFAULT_ITERATIONS,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val header = Header(
            version = VERSION,
            kdf = KDF,
            iterations = iterations,
            salt = Base64.getEncoder().encodeToString(salt),
            cipher = CIPHER,
            chunkSize = chunkSize,
        )
        val headerBytes = Json.encodeToString(header).encodeToByteArray()
        val key = deriveKey(password, salt, iterations)
        try {
            target.parentFile?.mkdirs()
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                // The payload is ciphertext, which does not compress; trying would only cost time.
                zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry(HEADER_FILE_NAME))
                zip.write(headerBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(PAYLOAD_FILE_NAME))
                plainArchive.inputStream().buffered().use { seal(it, zip, key, headerBytes, chunkSize) }
                zip.closeEntry()
            }
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts the password-protected [archive] into [target], which then holds the ordinary backup archive
     * it was made from.
     *
     * A wrong password fails on the first chunk, before anything is written. A chunk that fails later means
     * the file itself is damaged; whatever had been written by then is deleted again, so [target] either
     * holds the whole archive or does not exist.
     */
    fun decrypt(archive: File, password: String, target: File) {
        ZipFile(archive).use { zip ->
            val headerEntry = zip.getEntry(HEADER_FILE_NAME) ?: throw UnreadableArchiveException("No header")
            val payloadEntry = zip.getEntry(PAYLOAD_FILE_NAME) ?: throw UnreadableArchiveException("No payload")
            val headerBytes = zip.getInputStream(headerEntry).use { it.readAtMost(MAX_HEADER_BYTES) }
            val header = parseHeader(headerBytes)
            val salt = Base64.getDecoder().decode(header.salt)
            val key = deriveKey(password, salt, header.iterations)
            try {
                target.outputStream().buffered().use { out ->
                    zip.getInputStream(payloadEntry).buffered().use { open(it, out, key, headerBytes, header.chunkSize) }
                }
            } catch (e: ZipException) {
                target.delete()
                throw UnreadableArchiveException("Damaged payload: ${e.message}")
            } catch (e: Throwable) {
                target.delete()
                throw e
            } finally {
                key.fill(0)
            }
        }
    }

    private fun parseHeader(bytes: ByteArray): Header {
        val header = runCatching { HeaderJson.decodeFromString<Header>(bytes.decodeToString()) }
            .getOrElse { throw UnreadableArchiveException("Unreadable header") }
        if (header.version != VERSION || header.kdf != KDF || header.cipher != CIPHER) {
            throw UnreadableArchiveException("Unsupported format ${header.version}/${header.kdf}/${header.cipher}")
        }
        if (header.iterations !in 1..MAX_ITERATIONS || header.chunkSize !in CHUNK_SIZES) {
            throw UnreadableArchiveException("Header out of bounds")
        }
        val saltOk = runCatching { Base64.getDecoder().decode(header.salt).size >= SALT_BYTES }.getOrDefault(false)
        if (!saltOk) throw UnreadableArchiveException("Bad salt")
        return header
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018) of the normalised [password]; see the class comment for why by hand. */
    internal fun deriveKey(password: String, salt: ByteArray, iterations: Int, length: Int = KEY_BYTES): ByteArray {
        val passwordBytes = Normalizer.normalize(password, Normalizer.Form.NFKC).encodeToByteArray()
        require(passwordBytes.isNotEmpty()) { "Empty password" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(passwordBytes, HMAC))
        passwordBytes.fill(0)
        val blockSize = mac.macLength
        val out = ByteArray(length)
        val u = ByteArray(blockSize)
        val t = ByteArray(blockSize)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(out, offset, 0, minOf(blockSize, length - offset))
            offset += blockSize
            block++
        }
        u.fill(0)
        t.fill(0)
        return out
    }

    /**
     * Seals [input] chunk by chunk into [output]. A chunk shorter than [chunkSize] is the last one, so an
     * input that is an exact multiple of it ends in an empty chunk: only that way does the end carry a flag.
     */
    internal fun seal(input: InputStream, output: OutputStream, key: ByteArray, aad: ByteArray, chunkSize: Int) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val plain = ByteArray(chunkSize)
        val sealed = ByteArray(chunkSize + TAG_BYTES)
        var index = 0L
        while (true) {
            val read = input.readFully(plain)
            val last = read < chunkSize
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BYTES * 8, nonce(index, last)))
            cipher.updateAAD(aad)
            output.write(sealed, 0, cipher.doFinal(plain, 0, read, sealed, 0))
            if (last) break
            index++
        }
        plain.fill(0)
    }

    /** The inverse of [seal]. Each chunk is checked before a byte of it is written. */
    internal fun open(input: InputStream, output: OutputStream, key: ByteArray, aad: ByteArray, chunkSize: Int) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val sealed = ByteArray(chunkSize + TAG_BYTES)
        val plain = ByteArray(chunkSize)
        var index = 0L
        while (true) {
            val read = input.readFully(sealed)
            // Short of a full chunk means this is the last one; short of a tag means the last one is missing.
            val last = read < sealed.size
            if (read < TAG_BYTES) throw UnreadableArchiveException("Cut off after chunk $index")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BYTES * 8, nonce(index, last)))
            cipher.updateAAD(aad)
            val written = try {
                cipher.doFinal(sealed, 0, read, plain, 0)
            } catch (e: AEADBadTagException) {
                if (index == 0L) throw WrongPasswordException()
                throw UnreadableArchiveException("Chunk $index does not check out")
            }
            output.write(plain, 0, written)
            if (last) break
            index++
        }
        plain.fill(0)
    }

    /** Eleven bytes of big-endian chunk index, then 1 for the last chunk and 0 for any other. */
    private fun nonce(index: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        for (i in 0 until Long.SIZE_BYTES) {
            nonce[NONCE_BYTES - 2 - i] = (index ushr (8 * i)).toByte()
        }
        nonce[NONCE_BYTES - 1] = if (last) 1 else 0
        return nonce
    }

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit + 1)
        val read = readFully(buffer)
        if (read > limit) throw UnreadableArchiveException("Header too large")
        return buffer.copyOf(read)
    }
}
