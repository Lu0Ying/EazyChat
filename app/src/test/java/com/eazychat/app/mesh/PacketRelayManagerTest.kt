package com.eazychat.app.mesh

import com.eazychat.app.model.RoutedPacket
import com.eazychat.app.protocol.BitchatPacket
import com.eazychat.app.protocol.MessageType
import com.eazychat.app.protocol.SpecialRecipients
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for multi-hop relay behaviour in [PacketRelayManager].
 */
class PacketRelayManagerTest {

    companion object {
        private const val MY_PEER_ID = "0011223344556677"
        private const val PEER_A = "aabbccddeeff0011"
        private const val PEER_B = "1122334455667788"
    }

    @Test
    fun `ttl is decremented by one when relaying`() = runBlocking {
        val delegate = FakeDelegate(networkSize = 2)
        val manager = PacketRelayManager(MY_PEER_ID).apply { this.delegate = delegate }

        manager.handlePacketRelay(RoutedPacket(packet(ttl = 7), PEER_A))

        assertEquals(1, delegate.relayed.size)
        assertEquals(6u.toUByte(), delegate.relayed.single().packet.ttl)
    }

    @Test
    fun `ttl zero terminates forwarding`() = runBlocking {
        val delegate = FakeDelegate(networkSize = 2)
        val manager = PacketRelayManager(MY_PEER_ID).apply { this.delegate = delegate }

        manager.handlePacketRelay(RoutedPacket(packet(ttl = 0), PEER_A))

        assertTrue(delegate.relayed.isEmpty())
    }

    @Test
    fun `our own packet is not relayed`() = runBlocking {
        val delegate = FakeDelegate(networkSize = 2)
        val manager = PacketRelayManager(MY_PEER_ID).apply { this.delegate = delegate }

        manager.handlePacketRelay(RoutedPacket(packet(ttl = 7), MY_PEER_ID))

        assertTrue(delegate.relayed.isEmpty())
    }

    @Test
    fun `packet addressed to us is not relayed`() = runBlocking {
        val delegate = FakeDelegate(networkSize = 2)
        val manager = PacketRelayManager(MY_PEER_ID).apply { this.delegate = delegate }

        manager.handlePacketRelay(RoutedPacket(packet(ttl = 7, recipient = MY_PEER_ID), PEER_A))

        assertTrue(delegate.relayed.isEmpty())
    }

    @Test
    fun `broadcast recipient is treated as broadcast and relayed`() = runBlocking {
        val delegate = FakeDelegate(networkSize = 2)
        val manager = PacketRelayManager(MY_PEER_ID).apply { this.delegate = delegate }

        manager.handlePacketRelay(
            RoutedPacket(packet(ttl = 7, recipientBytes = SpecialRecipients.BROADCAST), PEER_A)
        )

        assertEquals(1, delegate.relayed.size)
    }

    @Test
    fun `same packet arriving through two neighbors is relayed only once`() = runBlocking {
        val delegate = FakeDelegate(networkSize = 2)
        val manager = PacketRelayManager(MY_PEER_ID).apply { this.delegate = delegate }
        val duplicate = packet(ttl = 7)

        manager.handlePacketRelay(RoutedPacket(duplicate, PEER_A))
        manager.handlePacketRelay(RoutedPacket(duplicate, PEER_B))

        assertEquals(1, delegate.relayed.size)
        assertEquals(PEER_A, delegate.relayed.single().peerID)
    }

    @Test
    fun `high ttl relays even in a large network`() = runBlocking {
        val delegate = FakeDelegate(networkSize = 200)
        val manager = PacketRelayManager(MY_PEER_ID).apply { this.delegate = delegate }

        manager.handlePacketRelay(RoutedPacket(packet(ttl = 7), PEER_A))

        assertEquals(1, delegate.relayed.size)
    }

    @Test
    fun `low ttl relays in a small network`() = runBlocking {
        val delegate = FakeDelegate(networkSize = 3)
        val manager = PacketRelayManager(MY_PEER_ID).apply { this.delegate = delegate }

        manager.handlePacketRelay(RoutedPacket(packet(ttl = 1), PEER_A))

        assertEquals(1, delegate.relayed.size)
    }

    @Test
    fun `relay probability boundaries match network size thresholds`() {
        val manager = PacketRelayManager(MY_PEER_ID)

        assertEquals(1.0, manager.relayProbabilityForNetworkSize(1), 0.0)
        assertEquals(1.0, manager.relayProbabilityForNetworkSize(10), 0.0)
        assertEquals(0.85, manager.relayProbabilityForNetworkSize(11), 0.0)
        assertEquals(0.85, manager.relayProbabilityForNetworkSize(30), 0.0)
        assertEquals(0.7, manager.relayProbabilityForNetworkSize(31), 0.0)
        assertEquals(0.7, manager.relayProbabilityForNetworkSize(50), 0.0)
        assertEquals(0.55, manager.relayProbabilityForNetworkSize(51), 0.0)
        assertEquals(0.55, manager.relayProbabilityForNetworkSize(100), 0.0)
        assertEquals(0.4, manager.relayProbabilityForNetworkSize(101), 0.0)
    }

    private fun packet(
        ttl: Int,
        recipient: String? = null,
        recipientBytes: ByteArray? = null,
        payload: String = "relay-test"
    ): BitchatPacket {
        val recipientID = recipientBytes ?: recipient?.let { hexToBytes(it) }
        return BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToBytes(PEER_A),
            recipientID = recipientID,
            timestamp = 1_700_000_000_000uL,
            payload = payload.toByteArray(),
            signature = null,
            ttl = ttl.toUByte()
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private class FakeDelegate(private val networkSize: Int) : PacketRelayManagerDelegate {
        val relayed = mutableListOf<RoutedPacket>()

        override fun getNetworkSize(): Int = networkSize
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun broadcastPacket(routed: RoutedPacket) {
            relayed.add(routed)
        }
    }
}
