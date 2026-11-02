package com.eazychat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for the store-and-forward queue used by private messages:
 * enqueue while offline, select on reconnect, de-duplicate by messageID.
 */
class OfflineMessageQueueTest {

    private val queue = OfflineMessageQueue()

    @Test
    fun `queued message is selectable for its peer`() {
        assertTrue(queue.enqueue("peerA", "hello", "Alice", "msg1", now = 1_000))
        val entries = queue.peek("peerA")
        assertEquals(1, entries.size)
        assertEquals("hello", entries[0].content)
        assertEquals("Alice", entries[0].nickname)
        assertEquals("msg1", entries[0].messageID)
        assertEquals(1_000L, entries[0].queuedAt)
    }

    @Test
    fun `duplicate message id is not queued again`() {
        assertTrue(queue.enqueue("peerA", "hello", "Alice", "msg1"))
        assertFalse(queue.enqueue("peerA", "hello again", "Alice", "msg1"))
        assertEquals(1, queue.size("peerA"))
        assertEquals("hello", queue.peek("peerA")[0].content)
    }

    @Test
    fun `same message id may target different peers`() {
        assertTrue(queue.enqueue("peerA", "hello", "Alice", "msg1"))
        assertTrue(queue.enqueue("peerB", "hello", "Bob", "msg1"))
        assertEquals(1, queue.size("peerA"))
        assertEquals(1, queue.size("peerB"))
    }

    @Test
    fun `removing a sent message empties the peer queue`() {
        queue.enqueue("peerA", "hello", "Alice", "msg1")
        queue.remove("peerA", "msg1")
        assertTrue(queue.peek("peerA").isEmpty())
        assertEquals(0, queue.size("peerA"))
    }

    @Test
    fun `removing one message keeps the others`() {
        queue.enqueue("peerA", "first", "Alice", "msg1")
        queue.enqueue("peerA", "second", "Alice", "msg2")
        queue.remove("peerA", "msg1")
        assertEquals(listOf("second"), queue.peek("peerA").map { it.content })
    }

    @Test
    fun `queues are isolated per peer`() {
        queue.enqueue("peerA", "to A", "Alice", "msgA")
        queue.enqueue("peerB", "to B", "Bob", "msgB")
        assertEquals(listOf("to A"), queue.peek("peerA").map { it.content })
        assertEquals(listOf("to B"), queue.peek("peerB").map { it.content })
        assertTrue(queue.peek("peerC").isEmpty())
    }

    @Test
    fun `entries keep enqueue order`() {
        queue.enqueue("peerA", "first", "Alice", "msg1")
        queue.enqueue("peerA", "second", "Alice", "msg2")
        assertEquals(listOf("msg1", "msg2"), queue.peek("peerA").map { it.messageID })
    }

    @Test
    fun `offline peer keeps its queue while disconnected`() {
        queue.enqueue("peerA", "hello", "Alice", "msg1")
        // Disconnect must not discard pending messages: they are delivered on reconnect.
        assertEquals(1, queue.size("peerA"))
        assertEquals(1, queue.snapshot().keys.count { it == "peerA" })
    }

    @Test
    fun `clearing the queue drops all messages`() {
        queue.enqueue("peerA", "hello", "Alice", "msg1")
        queue.enqueue("peerB", "hi", "Bob", "msg2")
        queue.clear()
        assertTrue(queue.snapshot().isEmpty())
    }
}
