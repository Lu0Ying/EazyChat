package com.eazychat.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TLV construction / parsing of the private-message payload that is encrypted by Noise.
 * Pure JVM, no Android or BLE involved.
 */
class PrivateMessagePacketTest {

    @Test
    fun `private message TLV round-trips id and content`() {
        val original = PrivateMessagePacket(messageID = "ABC-123", content = "晚上好，见到你很高兴")
        val encoded = original.encode()

        assertEquals(original, PrivateMessagePacket.decode(encoded!!))
    }

    @Test
    fun `oversized content is rejected instead of truncated`() {
        val tooLong = PrivateMessagePacket(messageID = "ABC-123", content = "x".repeat(256))

        assertNull(tooLong.encode())
    }

    @Test
    fun `malformed TLV is rejected`() {
        assertNull(PrivateMessagePacket.decode(byteArrayOf(0x00, 0x05, 0x41))) // declared length exceeds data
        assertNull(PrivateMessagePacket.decode(byteArrayOf(0x02)))            // unknown TLV type
        assertNull(PrivateMessagePacket.decode(ByteArray(0)))                 // empty
    }

    @Test
    fun `noise payload prefixes the type byte and round-trips`() {
        val data = PrivateMessagePacket("ABC-123", "hi").encode()!!
        val payload = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, data)
        val encoded = payload.encode()

        assertEquals(NoisePayloadType.PRIVATE_MESSAGE.value.toByte(), encoded[0])
        assertEquals(payload, NoisePayload.decode(encoded))
    }

    @Test
    fun `noise payload rejects empty data and unknown types`() {
        assertNull(NoisePayload.decode(ByteArray(0)))
        assertNull(NoisePayload.decode(byteArrayOf(0x7F)))
    }

    @Test
    fun `delivery ack payload carries the message id as plain utf8`() {
        val messageID = "ABC-123"
        val payload = NoisePayload(NoisePayloadType.DELIVERED, messageID.toByteArray(Charsets.UTF_8))
        val decoded = NoisePayload.decode(payload.encode())

        assertEquals(NoisePayloadType.DELIVERED, decoded?.type)
        assertEquals(messageID, String(decoded!!.data, Charsets.UTF_8))
    }

    @Test
    fun `payload types map to the iOS wire values`() {
        assertEquals(NoisePayloadType.PRIVATE_MESSAGE, NoisePayloadType.fromValue(0x01u))
        assertEquals(NoisePayloadType.READ_RECEIPT, NoisePayloadType.fromValue(0x02u))
        assertEquals(NoisePayloadType.DELIVERED, NoisePayloadType.fromValue(0x03u))
        assertEquals(NoisePayloadType.FILE_TRANSFER, NoisePayloadType.fromValue(0x20u))
    }

    @Test
    fun `payload equality is content based`() {
        val a = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, byteArrayOf(1, 2, 3))
        val b = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, byteArrayOf(1, 2, 3))
        val c = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, byteArrayOf(1, 2, 4))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
        assertNotEquals(a, c)
        assertTrue(NoisePayloadType.values().size >= 4)
    }
}
