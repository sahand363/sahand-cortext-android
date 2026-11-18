package com.sahand.cortextchat

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The app-layer cipher from PROTOCOL-v2.md (HMAC-SHA256 stream cipher +
 * encrypt-then-MAC). Standard library only: javax.crypto.Mac
 * (HmacSHA256) + MessageDigest (SHA-256) + android.util.Base64.
 *
 * The "secret" here is the mobile token copied from the hub's Mobile
 * page: it is BOTH the encryption key and the auth credential. The
 * hub verifies the token by decrypting the request body — the token
 * itself is never sent over the network.
 *
 * - Key: SHA-256(token UTF-8) -> 32 bytes.
 * - Nonce: 16 random bytes per message (never reused with the same key).
 * - Keystream: block i (from 0) = HMAC-SHA256(key, nonce || counter_be32(i)),
 *   concatenated and truncated to the plaintext length.
 * - Ciphertext: plaintext XOR keystream.
 * - Tag: HMAC-SHA256(key, nonce || ciphertext) -> 32 bytes. The receiver
 *   MUST verify the tag (constant-time) BEFORE decrypting.
 * - Wire: base64(nonce [16] || ciphertext || tag [32]).
 *
 * This is what makes the chat private even over plain HTTP with no SSL:
 * the content is encrypted before it ever touches the network, and TLS
 * (when a certificate is present) is a second layer on top.
 */
object Crypto {

    // Test vector from PROTOCOL-v2.md — selfCheck() must pass against it.
    private const val TEST_TOKEN = "test-secret-123"
    private const val TEST_NONCE_HEX = "000102030405060708090a0b0c0d0e0f"
    private const val TEST_PLAINTEXT = """{"message":"hello"}"""
    private const val TEST_WIRE =
        "AAECAwQFBgcICQoLDA0OD1CxYsjP4IRfQQs0UaEG6ViEjDW1P5Rc6LQISBhvZyxCquBKupBOD18iUvQPMydvXTWDrA=="

    private val random = SecureRandom()

    private fun key(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun keystream(key: ByteArray, nonce: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var produced = 0
        var counter = 0
        while (produced < length) {
            // counter as a 4-byte big-endian unsigned int
            val counterBytes = byteArrayOf(
                ((counter ushr 24) and 0xFF).toByte(),
                ((counter ushr 16) and 0xFF).toByte(),
                ((counter ushr 8) and 0xFF).toByte(),
                (counter and 0xFF).toByte()
            )
            val block = hmac(key, nonce + counterBytes)
            val take = minOf(block.size, length - produced)
            block.copyInto(out, produced, 0, take)
            produced += take
            counter++
        }
        return out
    }

    /** Encrypt plaintext bytes with the token. Returns the base64 wire. */
    fun encrypt(token: String, plaintext: ByteArray): String {
        val nonce = ByteArray(16).also { random.nextBytes(it) }
        return encryptWithNonce(token, nonce, plaintext)
    }

    /** Encrypt with a fixed nonce (used by the test-vector self-check). */
    fun encryptWithNonce(token: String, nonce: ByteArray, plaintext: ByteArray): String {
        val k = key(token)
        val ks = keystream(k, nonce, plaintext.size)
        val ct = ByteArray(plaintext.size) { i -> (plaintext[i].toInt() xor ks[i].toInt()).toByte() }
        val tag = hmac(k, nonce + ct)
        return Base64.encodeToString(nonce + ct + tag, Base64.NO_WRAP)
    }

    /** Decrypt a base64 wire with the token. Throws on a bad tag or
     *  malformed input. Never returns plaintext when the tag fails. */
    fun decrypt(token: String, wire: String): ByteArray {
        val k = key(token)
        val raw = try {
            Base64.decode(wire.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad base64 wire")
        }
        if (raw.size < 16 + 32) throw IllegalArgumentException("wire too short")
        val nonce = raw.copyOfRange(0, 16)
        val tag = raw.copyOfRange(raw.size - 32, raw.size)
        val ct = raw.copyOfRange(16, raw.size - 32)
        val expected = hmac(k, nonce + ct)
        // Constant-time compare: never decrypt before the tag checks out.
        if (!MessageDigest.isEqual(expected, tag)) {
            throw SecurityException("tag mismatch — wrong token or tampered message")
        }
        val ks = keystream(k, nonce, ct.size)
        return ByteArray(ct.size) { i -> (ct[i].toInt() xor ks[i].toInt()).toByte() }
    }

    /** Runs the PROTOCOL-v2.md test vector. Returns true only if encryption
     *  produces the exact expected wire, decryption of that wire gives
     *  back the plaintext, and a tampered wire is rejected by the tag. */
    fun selfCheck(): Boolean {
        return try {
            val nonce = TEST_NONCE_HEX.chunked(2)
                .map { it.toInt(16).toByte() }.toByteArray()
            val wire = encryptWithNonce(
                TEST_TOKEN, nonce, TEST_PLAINTEXT.toByteArray(Charsets.UTF_8)
            )
            if (wire != TEST_WIRE) return false
            val back = decrypt(TEST_TOKEN, TEST_WIRE).toString(Charsets.UTF_8)
            if (back != TEST_PLAINTEXT) return false
            // Flip one ciphertext byte: the tag check must reject it.
            val raw = Base64.decode(TEST_WIRE, Base64.DEFAULT)
            raw[16] = (raw[16].toInt() xor 1).toByte()
            val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)
            try {
                decrypt(TEST_TOKEN, tampered)
                false // must have thrown
            } catch (e: SecurityException) {
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
