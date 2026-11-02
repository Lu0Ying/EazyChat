package com.eazychat.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/**
 * Wire-format tests for the structured broadcast payload used by channel / mention messages.
 *
 * Channel messages used to be broadcast as raw text, which meant receivers could not tell which
 * channel a message belonged to. The marker byte keeps plain text and structured payloads apart.
 */
class BitchatMessageWireTest {

    @Test
    fun `structured payload keeps channel mentions and sender`() {
        val message = BitchatMessage(
            sender = "alice",
            content = "hello #room",
            timestamp = Date(1_700_000_000_000),
            senderPeerID = "AABBCCDDEEFF0011",
            mentions = listOf("bob"),
            channel = "#room"
        )

        val payload = message.toStructuredPayload()
        assertNotNull(payload)
        assertEquals(0x00.toByte(), payload!![0])

        val parsed = BitchatMessage.fromStructuredPayload(payload)
        assertNotNull(parsed)
        assertEquals("#room", parsed!!.channel)
        assertEquals(listOf("bob"), parsed.mentions)
        assertEquals("AABBCCDDEEFF0011", parsed.senderPeerID)
        assertEquals("hello #room", parsed.content)
        assertEquals("alice", parsed.sender)
        assertEquals(message.id, parsed.id)
        assertEquals(message.timestamp.time, parsed.timestamp.time)
    }

    @Test
    fun `plain chat text is not mistaken for a structured payload`() {
        assertNull(BitchatMessage.fromStructuredPayload("hello #room".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `structured payload without channel still round-trips mentions`() {
        val message = BitchatMessage(
            sender = "alice",
            content = "hi bob",
            timestamp = Date(1_700_000_000_001),
            mentions = listOf("bob")
        )

        val parsed = BitchatMessage.fromStructuredPayload(message.toStructuredPayload()!!)
        assertNotNull(parsed)
        assertNull(parsed!!.channel)
        assertEquals(listOf("bob"), parsed.mentions)
    }

    @Test
    fun `marker without body is rejected`() {
        assertNull(BitchatMessage.fromStructuredPayload(byteArrayOf(0x00)))
    }
}
