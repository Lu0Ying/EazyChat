package com.eazychat.app.ui

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure (Android-free) channel key derivation and password verification helpers.
 *
 * Kept separate from [ChannelManager] so the password check can be unit tested on the JVM:
 * the previous implementation always returned `true`, which silently accepted any password.
 */
internal object ChannelCrypto {

    private const val ITERATIONS = 100_000 // 100,000 iterations (same as iOS)
    private const val KEY_BITS = 256

    fun deriveKey(password: String, channelName: String): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), channelName.toByteArray(), ITERATIONS, KEY_BITS)
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }

    /** SHA-256 over the derived key bytes; stored locally so a wrong password can be rejected. */
    fun commitment(key: SecretKeySpec): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun commitmentFor(password: String, channelName: String): String = commitment(deriveKey(password, channelName))

    /**
     * A channel with no stored commitment has never been joined with a password before,
     * so there is nothing to compare against and the first password becomes the reference.
     * Once a commitment exists, only the exact same password (and channel) is accepted.
     */
    fun verifies(storedCommitment: String?, password: String, channelName: String): Boolean {
        if (storedCommitment == null) return true
        return storedCommitment == commitmentFor(password, channelName)
    }
}
