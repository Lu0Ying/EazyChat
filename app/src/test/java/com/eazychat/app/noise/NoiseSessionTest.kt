package com.eazychat.app.noise

import com.eazychat.app.noise.southernstorm.protocol.Noise
import com.eazychat.app.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs a real Noise XX handshake in the JVM (the southernstorm implementation is pure Java),
 * then exercises the transport path, replay protection and rekey threshold.
 *
 * No fake crypto here: the tests fail if the handshake, ChaCha20-Poly1305 transport or the
 * sliding-window replay protection regresses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoiseSessionTest {

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val dh = Noise.createDH("25519")
        dh.generateKeyPair()
        val privateKey = ByteArray(32).also { dh.getPrivateKey(it, 0) }
        val publicKey = ByteArray(32).also { dh.getPublicKey(it, 0) }
        dh.destroy()
        return privateKey to publicKey
    }

    /** Runs the full XX flow and returns an established (initiator, responder) pair. */
    private fun establishedPair(): Pair<NoiseSession, NoiseSession> {
        val (initiatorPriv, initiatorPub) = generateKeyPair()
        val (responderPriv, responderPub) = generateKeyPair()

        val initiator = NoiseSession("AAAABBBBCCCC0001", true, initiatorPriv, initiatorPub)
        val responder = NoiseSession("AAAABBBBCCCC0002", false, responderPriv, responderPub)

        val message1 = initiator.startHandshake()
        val message2 = responder.processHandshakeMessage(message1)
        assertNotNull("responder must produce XX message 2", message2)

        val message3 = initiator.processHandshakeMessage(message2!!)
        assertNotNull("initiator must produce XX message 3", message3)

        responder.processHandshakeMessage(message3!!)

        assertTrue("initiator should be established", initiator.isEstablished())
        assertTrue("responder should be established", responder.isEstablished())
        assertNotNull("remote static key should be available after XX", initiator.getRemoteStaticPublicKey())
        return initiator to responder
    }

    @Test
    fun `xx handshake establishes both sides`() {
        establishedPair()
    }

    @Test
    fun `transport encrypt and decrypt round-trips in both directions`() {
        val (initiator, responder) = establishedPair()

        val fromInitiator = initiator.encrypt("hello from initiator".toByteArray())
        assertEquals("hello from initiator", String(responder.decrypt(fromInitiator)))

        val fromResponder = responder.encrypt("hello from responder".toByteArray())
        assertEquals("hello from responder", String(initiator.decrypt(fromResponder)))
    }

    @Test
    fun `replayed payload is rejected`() {
        val (initiator, responder) = establishedPair()
        val payload = initiator.encrypt("only once".toByteArray())

        responder.decrypt(payload)

        try {
            responder.decrypt(payload)
            fail("replayed payload must be rejected by the replay window")
        } catch (_: SessionError.DecryptionFailed) {
            // expected
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val (initiator, responder) = establishedPair()
        val payload = initiator.encrypt("integrity".toByteArray())
        val tampered = payload.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }

        try {
            responder.decrypt(tampered)
            fail("tampered ciphertext must fail authentication")
        } catch (_: SessionError.DecryptionFailed) {
            // expected
        }
    }

    @Test
    fun `encrypting before the handshake completes is rejected`() {
        val (privateKey, publicKey) = generateKeyPair()
        val session = NoiseSession("AAAABBBBCCCC0003", true, privateKey, publicKey)

        try {
            session.encrypt("too early".toByteArray())
            fail("encrypt must require an established session")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `decrypting before the handshake completes is rejected`() {
        val (privateKey, publicKey) = generateKeyPair()
        val session = NoiseSession("AAAABBBBCCCC0004", false, privateKey, publicKey)

        try {
            session.decrypt(byteArrayOf(0, 0, 0, 1))
            fail("decrypt must require an established session")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `needs rekey once the session message limit is exceeded`() {
        val (initiator, _) = establishedPair()
        val limit = AppConstants.Noise.REKEY_MESSAGE_LIMIT_ENCRYPTION.toInt()

        repeat(limit) { initiator.encrypt(byteArrayOf(1)) }
        assertFalse("at the limit the session still used", initiator.needsRekey())

        initiator.encrypt(byteArrayOf(1))
        assertTrue("above the limit a rekey is required", initiator.needsRekey())
    }

    @Test
    fun `reset returns the session to uninitialized`() {
        val (initiator, _) = establishedPair()

        initiator.reset()

        assertFalse(initiator.isEstablished())
        assertTrue(initiator.getState() is NoiseSession.NoiseSessionState.Uninitialized)
    }

    @Test
    fun `destroy marks the session as failed`() {
        val (initiator, _) = establishedPair()

        initiator.destroy()

        assertFalse(initiator.isEstablished())
        assertTrue(initiator.getState() is NoiseSession.NoiseSessionState.Failed)
    }
}
