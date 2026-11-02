package com.eazychat.app.mesh

import java.util.concurrent.ConcurrentHashMap

/**
 * Timestamped per-peer RSSI cache.
 *
 * GATT callbacks write samples here; UI and diagnostics only read this cache.
 * Samples older than [staleAfterMs] are filtered out, so the people panel never
 * shows a signal reading taken minutes ago.
 */
class RssiCache(private val staleAfterMs: Long) {

    private data class Sample(val rssi: Int, val updatedAt: Long)

    private val samples = ConcurrentHashMap<String, Sample>()

    /**
     * Store the latest reading for a peer, replacing any previous sample.
     */
    fun update(peerID: String, rssi: Int, now: Long = System.currentTimeMillis()) {
        if (peerID == "unknown") return
        samples[peerID] = Sample(rssi, now)
    }

    /**
     * Fresh readings only.
     */
    fun snapshot(now: Long = System.currentTimeMillis()): Map<String, Int> {
        return samples.entries
            .filter { isFresh(it.value, now) }
            .associate { it.key to it.value.rssi }
    }

    /**
     * Latest reading for one peer, or null when missing/expired.
     */
    fun value(peerID: String, now: Long = System.currentTimeMillis()): Int? {
        val sample = samples[peerID] ?: return null
        return if (isFresh(sample, now)) sample.rssi else null
    }

    fun remove(peerID: String) {
        samples.remove(peerID)
    }

    fun clear() {
        samples.clear()
    }

    fun size(): Int = samples.size

    private fun isFresh(sample: Sample, now: Long): Boolean {
        return now - sample.updatedAt <= staleAfterMs
    }
}
