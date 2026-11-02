package com.eazychat.app.mesh

import com.eazychat.app.crypto.EncryptionService
import com.eazychat.app.crypto.PacketSignatureVerifier
import com.eazychat.app.model.RoutedPacket
import com.eazychat.app.protocol.BitchatPacket
import com.eazychat.app.protocol.MessageType
import com.eazychat.app.protocol.SpecialRecipients
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.SecureRandom

/**
 * Offline tests for broadcast signature enforcement.
 *
 * Covers the production gate used before a packet is displayed or relayed:
 *  - unsigned legacy packet -> accepted (compatibility)
 *  - signed packet -> must verify against the sender's announced Ed25519 key
 *  - invalid signature -> dropped before MessageHandler and PacketRelayManager
 */
class BroadcastSignatureTest {

    companion object {
        private const val MY_PEER_ID = "0011223344556677"
        private const val SENDER_PEER_ID = "aabbccddeeff0011"
    }

    private val senderKeys = generateKeyPair()
    private val otherKeys = generateKeyPair()

    @Test
    fun `valid signature passes validation`() {
        val manager = securityManager(senderKeys.second)
        assertTrue(manager.validatePacket(signedMessage(), SENDER_PEER_ID))
    }

    @Test
    fun `tampered signature fails validation`() {
        val signed = signedMessage()
        val tampered = signed.copy(signature = signed.signature!!.clone().also { it[0] = (it[0] + 1).toByte() })
        assertFalse(securityManager(senderKeys.second).validatePacket(tampered, SENDER_PEER_ID))
    }

    @Test
    fun `tampered payload fails validation`() {
        val signed = signedMessage()
        val tampered = signed.copy(payload = signed.payload + "extra".toByteArray())
        assertFalse(securityManager(senderKeys.second).validatePacket(tampered, SENDER_PEER_ID))
    }

    @Test
    fun `signature made with another key fails validation`() {
        val signedByOther = signedMessage(otherKeys.first)
        assertFalse(securityManager(senderKeys.second).validatePacket(signedByOther, SENDER_PEER_ID))
    }

    @Test
    fun `unsigned legacy packet is accepted`() {
        val legacy = broadcastPacket(signature = null)
        assertTrue(securityManager(senderKeys.second).validatePacket(legacy, SENDER_PEER_ID))
    }

    @Test
    fun `signed packet without announced key is rejected`() {
        val manager = securityManager(null)
        assertFalse(manager.validatePacket(signedMessage(), SENDER_PEER_ID))
    }

    @Test
    fun `invalid signature never reaches the relay queue`() = runBlocking {
        val manager = securityManager(senderKeys.second)
        val delegate = RecordingProcessorDelegate(manager)
        val processor = PacketProcessor(MY_PEER_ID)
        processor.delegate = delegate

        val signed = signedMessage()
        val tampered = signed.copy(signature = signed.signature!!.clone().also { it[1] = (it[1] + 1).toByte() })
        processor.handleReceivedPacket(RoutedPacket(tampered, SENDER_PEER_ID))

        assertEquals(0, delegate.handled.size)
        assertEquals(0, delegate.relayed.size)
    }

    @Test
    fun `unsigned legacy broadcast is still handled and relayed`() = runBlocking {
        val manager = securityManager(senderKeys.second)
        val delegate = RecordingProcessorDelegate(manager)
        val processor = PacketProcessor(MY_PEER_ID)
        processor.delegate = delegate

        processor.handleReceivedPacket(RoutedPacket(broadcastPacket(signature = null), SENDER_PEER_ID))

        assertEquals(1, delegate.handled.size)
        assertEquals(1, delegate.relayed.size)
    }

    private fun securityManager(announcedSigningKey: ByteArray?): SecurityManager {
        val encryption = mock<EncryptionService>()
        whenever(encryption.verifyEd25519Signature(any<ByteArray>(), any<ByteArray>(), any<ByteArray>()))
            .thenAnswer { invocation ->
                PacketSignatureVerifier.verifyEd25519(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)
                )
            }

        val manager = SecurityManager(encryption, MY_PEER_ID)
        manager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) = Unit
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) = Unit
            override fun getPeerInfo(peerID: String): PeerInfo? {
                if (peerID != SENDER_PEER_ID) return null
                return PeerInfo(
                    id = SENDER_PEER_ID,
                    nickname = "alice",
                    isConnected = true,
                    isDirectConnection = true,
                    noisePublicKey = ByteArray(32),
                    signingPublicKey = announcedSigningKey,
                    isVerifiedNickname = true,
                    lastSeen = 0L
                )
            }
        }
        return manager
    }

    private fun broadcastPacket(signature: ByteArray?, payload: ByteArray = "hello mesh".toByteArray()): BitchatPacket {
        return BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToBytes(SENDER_PEER_ID),
            recipientID = null,
            timestamp = 1_700_000_000_000uL,
            payload = payload,
            signature = signature,
            ttl = 7u
        )
    }

    private fun signedMessage(privateKey: ByteArray = senderKeys.first): BitchatPacket {
        val unsigned = broadcastPacket(signature = null)
        val canonical = unsigned.toBinaryDataForSigning()!!
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(canonical, 0, canonical.size)
        return unsigned.copy(signature = signer.generateSignature())
    }

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        val privateKey = (pair.private as Ed25519PrivateKeyParameters).encoded
        val publicKey = (pair.public as Ed25519PublicKeyParameters).encoded
        return privateKey to publicKey
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private class RecordingProcessorDelegate(
        private val securityManager: SecurityManager
    ) : PacketProcessorDelegate {
        val handled = mutableListOf<RoutedPacket>()
        val relayed = mutableListOf<RoutedPacket>()

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
            return securityManager.validatePacket(packet, peerID)
        }

        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = "alice"
        override fun getNetworkSize(): Int = 2
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket): Boolean = false
        override fun handleNoiseEncrypted(routed: RoutedPacket) = Unit
        override fun handleAnnounce(routed: RoutedPacket) = Unit
        override fun handleMessage(routed: RoutedPacket) {
            handled.add(routed)
        }
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) {
            relayed.add(routed)
        }
    }
}
