package com.eazychat.app.services

import java.util.concurrent.ConcurrentHashMap

/**
 * Store-and-forward queue for private messages addressed to a peer that is not
 * reachable yet.
 *
 * Entries are de-duplicated by [Entry.messageID], so retrying a send can never
 * queue the same message twice. All access is synchronized because enqueue runs
 * on the UI thread while flushes run on service coroutines.
 */
class OfflineMessageQueue {

    data class Entry(
        val peerID: String,
        val content: String,
        val nickname: String,
        val messageID: String,
        val queuedAt: Long
    )

    private val queues = ConcurrentHashMap<String, MutableList<Entry>>()

    /**
     * @return true when the message was added, false when the same messageID is
     * already queued for this peer.
     */
    @Synchronized
    fun enqueue(
        peerID: String,
        content: String,
        nickname: String,
        messageID: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val queue = queues.getOrPut(peerID) { mutableListOf() }
        if (queue.any { it.messageID == messageID }) return false
        queue.add(Entry(peerID, content, nickname, messageID, now))
        return true
    }

    @Synchronized
    fun peek(peerID: String): List<Entry> {
        return queues[peerID]?.toList() ?: emptyList()
    }

    @Synchronized
    fun remove(peerID: String, messageID: String) {
        val queue = queues[peerID] ?: return
        queue.removeAll { it.messageID == messageID }
        if (queue.isEmpty()) queues.remove(peerID)
    }

    @Synchronized
    fun clearPeer(peerID: String) {
        queues.remove(peerID)
    }

    @Synchronized
    fun snapshot(): Map<String, List<Entry>> {
        return queues.mapValues { it.value.toList() }
    }

    @Synchronized
    fun size(peerID: String): Int {
        return queues[peerID]?.size ?: 0
    }

    @Synchronized
    fun clear() {
        queues.clear()
    }
}
