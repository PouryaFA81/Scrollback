package app.scrollback.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 hashing for the optional app-lock PIN. The PIN itself is never stored. */
object PinHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    data class Hashed(val salt: String, val hash: String)

    fun hash(pin: String, salt: ByteArray = newSalt()): Hashed {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val enc = Base64.getEncoder()
        return Hashed(enc.encodeToString(salt), enc.encodeToString(key))
    }

    fun verify(pin: String, saltB64: String, hashB64: String): Boolean {
        val salt = runCatching { Base64.getDecoder().decode(saltB64) }.getOrNull() ?: return false
        val candidate = Base64.getDecoder().decode(hash(pin, salt).hash)
        val expected = runCatching { Base64.getDecoder().decode(hashB64) }.getOrNull() ?: return false
        return MessageDigest.isEqual(candidate, expected)
    }

    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }

    private fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
}
