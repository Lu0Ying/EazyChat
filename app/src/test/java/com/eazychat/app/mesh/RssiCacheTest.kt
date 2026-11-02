package com.eazychat.app.mesh

import com.eazychat.app.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for the timestamped RSSI cache used by the people/RSSI panel.
 */
class RssiCacheTest {

    private val staleAfterMs = AppConstants.Mesh.RSSI_STALE_TIMEOUT_MS
    private val cache = RssiCache(staleAfterMs)

    @Test
    fun `latest reading is returned for a peer`() {
        cache.update("peer1", -70, now = 1_000)
        assertEquals(-70, cache.value("peer1", now = 1_000))
    }

    @Test
    fun `newer reading replaces the older one`() {
        cache.update("peer1", -80, now = 1_000)
        cache.update("peer1", -55, now = 2_000)
        assertEquals(-55, cache.value("peer1", now = 2_000))
        assertEquals(mapOf("peer1" to -55), cache.snapshot(now = 2_000))
    }

    @Test
    fun `reading exactly at the stale boundary is still shown`() {
        cache.update("peer1", -70, now = 1_000)
        assertEquals(-70, cache.value("peer1", now = 1_000 + staleAfterMs))
    }

    @Test
    fun `expired reading is hidden`() {
        cache.update("peer1", -70, now = 1_000)
        assertNull(cache.value("peer1", now = 1_001 + staleAfterMs))
        assertTrue(cache.snapshot(now = 1_001 + staleAfterMs).isEmpty())
    }

    @Test
    fun `snapshot keeps fresh peers and drops expired ones`() {
        val now = 1_000L + staleAfterMs + 1
        cache.update("fresh", -50, now = now - 1)
        cache.update("expired", -90, now = 1_000)
        assertEquals(mapOf("fresh" to -50), cache.snapshot(now = now))
    }

    @Test
    fun `removed peer disappears from the cache`() {
        cache.update("peer1", -70, now = 1_000)
        cache.remove("peer1")
        assertNull(cache.value("peer1", now = 1_000))
        assertTrue(cache.snapshot(now = 1_000).isEmpty())
    }

    @Test
    fun `clearing the cache drops every reading`() {
        cache.update("peerA", -60, now = 1_000)
        cache.update("peerB", -70, now = 1_000)
        cache.clear()
        assertEquals(0, cache.size())
        assertTrue(cache.snapshot(now = 1_000).isEmpty())
    }

    @Test
    fun `unknown peer id is ignored`() {
        cache.update("unknown", -99, now = 1_000)
        assertEquals(0, cache.size())
    }
}
