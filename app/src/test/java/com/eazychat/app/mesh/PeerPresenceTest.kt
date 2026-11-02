package com.eazychat.app.mesh

import com.eazychat.app.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for the single online-count rule: a peer is online when it is
 * still flagged connected and was seen within the stale timeout.
 */
class PeerPresenceTest {

    private val staleAfterMs = AppConstants.Mesh.STALE_PEER_TIMEOUT_MS
    private val now = 1_700_000_000_000L

    @Test
    fun `recently seen connected peers count as online`() {
        val peers = mapOf(
            "peerB" to peer("peerB", now - 1_000),
            "peerA" to peer("peerA", now - staleAfterMs)
        )
        assertEquals(listOf("peerA", "peerB"), PeerManager.activePeerIDs(peers, now, staleAfterMs))
    }

    @Test
    fun `stale peers are excluded`() {
        val peers = mapOf(
            "fresh" to peer("fresh", now - 1_000),
            "stale" to peer("stale", now - staleAfterMs - 1)
        )
        assertEquals(listOf("fresh"), PeerManager.activePeerIDs(peers, now, staleAfterMs))
    }

    @Test
    fun `disconnected peers are excluded even when recently seen`() {
        val peers = mapOf(
            "connected" to peer("connected", now),
            "offline" to peer("offline", now, isConnected = false)
        )
        assertEquals(listOf("connected"), PeerManager.activePeerIDs(peers, now, staleAfterMs))
    }

    @Test
    fun `multi-hop peers are counted alongside direct peers`() {
        val peers = mapOf(
            "direct" to peer("direct", now, isDirectConnection = true),
            "routed" to peer("routed", now, isDirectConnection = false)
        )
        assertEquals(listOf("direct", "routed"), PeerManager.activePeerIDs(peers, now, staleAfterMs))
    }

    @Test
    fun `empty network yields no online peers`() {
        assertTrue(PeerManager.activePeerIDs(emptyMap(), now, staleAfterMs).isEmpty())
    }

    private fun peer(
        id: String,
        lastSeen: Long,
        isConnected: Boolean = true,
        isDirectConnection: Boolean = false
    ) = PeerInfo(
        id = id,
        nickname = id,
        isConnected = isConnected,
        isDirectConnection = isDirectConnection,
        noisePublicKey = null,
        signingPublicKey = null,
        isVerifiedNickname = false,
        lastSeen = lastSeen
    )
}
