package com.eazychat.app.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the session manager orchestration (create / replace / remove, establishment callbacks)
 * with a real XX handshake, without any BLE dependency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoiseSessionManagerTest {

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val dh = com.eazychat.app.noise.southernstorm.protocol.Noise.createDH("25519")
        dh.generateKeyPair()
        val privateKey = ByteArray(32).also { dh.getPrivateKey(it, 0) }
        val publicKey = ByteArray(32).also { dh.getPublicKey(it, 0) }
        dh.destroy()
        return privateKey to publicKey
    }

    @Test
    fun `handshake through managers establishes both sessions and fires callbacks`() {
        val (aPriv, aPub) = generateKeyPair()
        val (bPriv, bPub) = generateKeyPair()
        val managerA = NoiseSessionManager(aPriv, aPub)
        val managerB = NoiseSessionManager(bPriv, bPub)

        var establishedAtA: String? = null
        var establishedAtB: String? = null
        managerA.onSessionEstablished = { peerID, _ -> establishedAtA = peerID }
        managerB.onSessionEstablished = { peerID, _ -> establishedAtB = peerID }

        val message1 = managerA.initiateHandshake("PEER_B")
        val message2 = managerB.processHandshakeMessage("PEER_A", message1)
        assertNotNull("responder must return XX message 2", message2)

        val message3 = managerA.processHandshakeMessage("PEER_B", message2!!)
        assertNotNull("initiator must return XX message 3", message3)

        managerB.processHandshakeMessage("PEER_A", message3!!)

        assertTrue(managerA.hasEstablishedSession("PEER_B"))
        assertTrue(managerB.hasEstablishedSession("PEER_A"))
        assertEquals("PEER_B", establishedAtA)
        assertEquals("PEER_A", establishedAtB)
        assertNotNull(managerA.getRemoteStaticKey("PEER_B"))
        assertNotNull(managerB.getRemoteStaticKey("PEER_A"))

        // Transport through the manager API
        val encrypted = managerA.encrypt("ping".toByteArray(), "PEER_B")
        assertEquals("ping", String(managerB.decrypt(encrypted, "PEER_A")))
    }

    @Test
    fun `encrypt without an established session is rejected`() {
        val (privateKey, publicKey) = generateKeyPair()
        val manager = NoiseSessionManager(privateKey, publicKey)
        manager.initiateHandshake("PEER_B") // handshake started but not completed

        try {
            manager.encrypt("data".toByteArray(), "PEER_B")
            org.junit.Assert.fail("encrypt must require an established session")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `decrypt without a session is rejected`() {
        val (privateKey, publicKey) = generateKeyPair()
        val manager = NoiseSessionManager(privateKey, publicKey)

        try {
            manager.decrypt(byteArrayOf(0, 0, 0, 1), "PEER_B")
            org.junit.Assert.fail("decrypt must require a session")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `initiate handshake replaces the previous session for the peer`() {
        val (privateKey, publicKey) = generateKeyPair()
        val manager = NoiseSessionManager(privateKey, publicKey)

        manager.initiateHandshake("PEER_B")
        assertTrue(manager.getSession("PEER_B")!!.isHandshaking())

        manager.initiateHandshake("PEER_B")

        assertTrue(manager.getSession("PEER_B")!!.isHandshaking())
        assertEquals(NoiseSession.NoiseSessionState.Handshaking, manager.getSessionState("PEER_B"))
    }

    @Test
    fun `remove session drops all session state`() {
        val (privateKey, publicKey) = generateKeyPair()
        val manager = NoiseSessionManager(privateKey, publicKey)
        manager.initiateHandshake("PEER_B")

        manager.removeSession("PEER_B")

        assertNull(manager.getSession("PEER_B"))
        assertFalse(manager.hasEstablishedSession("PEER_B"))
        assertTrue(manager.getSessionState("PEER_B") is NoiseSession.NoiseSessionState.Uninitialized)
    }
}
