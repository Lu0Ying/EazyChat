package com.eazychat.app.mesh

import com.eazychat.app.crypto.PacketSignatureStatus
import com.eazychat.app.crypto.PacketSignatureVerifier
import com.eazychat.app.model.FragmentPayload
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Random

/**
 * Offline tests proving that reassembled fragments keep the original signature,
 * so a tampered fragment set can never be displayed or re-relayed as a verified message.
 */
class FragmentSignatureTest {

    companion object {
        private const val MY_PEER_ID = "0011223344556677"
        private const val SENDER_PEER_ID = "aabbccddeeff0011"
    }

    private val senderKeys = generateKeyPair()

    @Test
    fun `reassembled packet keeps its signature and verifies`() {
        val manager = FragmentManager()
        try {
            val signed = signedLargePacket()
            val fragments = manager.createFragments(signed)
            assertTrue("Expected fragmentation, got ${fragments.size} part(s)", fragments.size > 1)

            val reassembled = reassemble(manager, fragments)
            assertNotNull(reassembled)
            assertNotNull(reassembled!!.signature)
            assertEquals(
                PacketSignatureStatus.VALID,
                PacketSignatureVerifier.verifyPacket(reassembled, senderKeys.second)
            )
            assertEquals(0u.toUByte(), reassembled.ttl)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `tampered fragment makes reassembled signature invalid`() {
        val manager = FragmentManager()
        try {
            val fragments = manager.createFragments(signedLargePacket())
            val reassembled = reassemble(manager, tamperOneFragment(fragments))
            assertNotNull(reassembled)
            assertEquals(
                PacketSignatureStatus.INVALID,
                PacketSignatureVerifier.verifyPacket(reassembled!!, senderKeys.second)
            )
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `verified reassembly is displayed but does not re-enter the relay queue`() = runBlocking {
        val manager = FragmentManager()
        try {
            val fragments = manager.createFragments(signedLargePacket())
            val processor = PacketProcessor(MY_PEER_ID)
            val delegate = RecordingDelegate(manager, senderKeys.second)
            processor.delegate = delegate

            fragments.forEach { processor.handleReceivedPacket(RoutedPacket(it, SENDER_PEER_ID)) }

            assertEquals(1, delegate.handledMessages.size)
            assertTrue(delegate.relayed.none { it.packet.type == MessageType.MESSAGE.value })
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `tampered reassembly is neither displayed nor relayed`() = runBlocking {
        val manager = FragmentManager()
        try {
            val fragments = tamperOneFragment(manager.createFragments(signedLargePacket()))
            val processor = PacketProcessor(MY_PEER_ID)
            val delegate = RecordingDelegate(manager, senderKeys.second)
            processor.delegate = delegate

            fragments.forEach { processor.handleReceivedPacket(RoutedPacket(it, SENDER_PEER_ID)) }

            assertTrue(delegate.handledMessages.isEmpty())
            assertTrue(delegate.relayed.none { it.packet.type == MessageType.MESSAGE.value })
        } finally {
            manager.shutdown()
        }
    }

    /**
     * Flip one byte in a data fragment (the last fragment ends with the signature bytes,
     * so the reassembled packet still decodes while its signature no longer matches).
     */
    private fun tamperOneFragment(fragments: List<BitchatPacket>): List<BitchatPacket> {
        val index = fragments.lastIndex
        val target = fragments[index]
        val payload = FragmentPayload.decode(target.payload)!!
        val data = payload.data.clone().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        return fragments.toMutableList().also {
            it[index] = target.copy(payload = payload.copy(data = data).encode())
        }
    }

    private fun reassemble(manager: FragmentManager, fragments: List<BitchatPacket>): BitchatPacket? {
        var reassembled: BitchatPacket? = null
        for (fragment in fragments) {
            manager.handleFragment(fragment)?.let { reassembled = it }
        }
        return reassembled
    }

    private fun signedLargePacket(): BitchatPacket {
        val payload = ByteArray(600).also { Random(42).nextBytes(it) }
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToBytes(SENDER_PEER_ID),
            recipientID = null,
            timestamp = 1_700_000_000_000uL,
            payload = payload,
            signature = null,
            ttl = 7u.toUByte()
        )
        val canonical = packet.toBinaryDataForSigning()!!
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(senderKeys.first, 0))
        signer.update(canonical, 0, canonical.size)
        return packet.copy(signature = signer.generateSignature())
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

    private class RecordingDelegate(
        private val fragmentManager: FragmentManager,
        private val senderPublicKey: ByteArray
    ) : PacketProcessorDelegate {
        val handledMessages = mutableListOf<RoutedPacket>()
        val relayed = mutableListOf<RoutedPacket>()

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
            val signature = packet.signature ?: return true
            return PacketSignatureVerifier.verifyPacket(packet, senderPublicKey) == PacketSignatureStatus.VALID
        }

        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = "alice"
        override fun getNetworkSize(): Int = 2
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket): Boolean = false
        override fun handleNoiseEncrypted(routed: RoutedPacket) = Unit
        override fun handleAnnounce(routed: RoutedPacket) = Unit
        override fun handleMessage(routed: RoutedPacket) {
            handledMessages.add(routed)
        }
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = fragmentManager.handleFragment(packet)
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) {
            relayed.add(routed)
        }
    }
}
