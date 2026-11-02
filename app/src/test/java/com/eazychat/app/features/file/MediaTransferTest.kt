package com.eazychat.app.features.file

import com.eazychat.app.mesh.FragmentManager
import com.eazychat.app.model.BitchatFilePacket
import com.eazychat.app.model.BitchatMessageType
import com.eazychat.app.model.FragmentPayload
import com.eazychat.app.protocol.BitchatPacket
import com.eazychat.app.protocol.MessagePadding
import com.eazychat.app.protocol.MessageType
import com.eazychat.app.protocol.SpecialRecipients
import com.eazychat.app.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Offline coverage for the ≤1MB media/file transfer path:
 * size limits, MIME classification, fragment counts and failure cleanup.
 */
class MediaTransferTest {

    companion object {
        private const val ONE_MB = AppConstants.Media.MAX_FILE_SIZE_BYTES
        private const val SENDER_PEER_ID = "aabbccddeeff0011"
    }

    @Test
    fun `size limit accepts up to one megabyte`() {
        assertTrue(FileUtils.isWithinSendLimit(0L))
        assertTrue(FileUtils.isWithinSendLimit(1L))
        assertTrue(FileUtils.isWithinSendLimit(ONE_MB - 1))
        assertTrue(FileUtils.isWithinSendLimit(ONE_MB))
    }

    @Test
    fun `size limit rejects one byte over one megabyte`() {
        assertFalse(FileUtils.isWithinSendLimit(ONE_MB + 1))
        assertFalse(FileUtils.isWithinSendLimit(2 * ONE_MB))
        assertFalse(FileUtils.isWithinSendLimit(20 * ONE_MB))
    }

    @Test
    fun `oversize notice states the limit`() {
        val notice = FileUtils.oversizeSendMessage(ONE_MB + 1)
        assertTrue(notice.contains("1 MB"))
        assertTrue(notice.contains("send cancelled"))
    }

    @Test
    fun `incoming file of exactly one megabyte is accepted`() {
        val file = BitchatFilePacket(
            fileName = "edge.bin",
            fileSize = ONE_MB,
            mimeType = "application/octet-stream",
            content = ByteArray(ONE_MB.toInt())
        )
        assertTrue(FileUtils.isIncomingWithinLimit(file))
    }

    @Test
    fun `incoming file over one megabyte is rejected`() {
        val oversizeContent = ByteArray(ONE_MB.toInt() + 1)
        val declared = BitchatFilePacket(
            fileName = "big.bin",
            fileSize = ONE_MB + 1,
            mimeType = "application/octet-stream",
            content = oversizeContent
        )
        assertFalse(FileUtils.isIncomingWithinLimit(declared))
        // A sender may under-declare the size; the decoded content must still be rejected.
        val underDeclared = declared.copy(fileSize = 1024)
        assertFalse(FileUtils.isIncomingWithinLimit(underDeclared))
    }

    @Test
    fun `mime type is derived from extension`() {
        assertEquals("image/jpeg", FileUtils.getMimeTypeFromExtension("photo.JPG"))
        assertEquals("audio/mp4", FileUtils.getMimeTypeFromExtension("voice.m4a"))
        assertEquals("application/pdf", FileUtils.getMimeTypeFromExtension("report.pdf"))
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromExtension("archive.xyz"))
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromExtension("noextension"))
    }

    @Test
    fun `mime type maps to message type`() {
        assertEquals(BitchatMessageType.Image, FileUtils.messageTypeForMime("image/png"))
        assertEquals(BitchatMessageType.Audio, FileUtils.messageTypeForMime("audio/m4a"))
        assertEquals(BitchatMessageType.File, FileUtils.messageTypeForMime("application/pdf"))
    }

    @Test
    fun `frame at the fragmentation threshold stays whole`() {
        val manager = FragmentManager()
        try {
            // v1 header 14 + sender 8 + payload 426 + signature 64 == 512 bytes
            val packet = signedPacket(payloadSize = 426)
            assertEquals(512, MessagePadding.unpad(packet.toBinaryData()!!).size)
            val fragments = manager.createFragments(packet)
            assertEquals(1, fragments.size)
            assertEquals(packet, fragments.first())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `frame one byte over the threshold splits into two fragments`() {
        val manager = FragmentManager()
        try {
            val packet = signedPacket(payloadSize = 427)
            assertEquals(513, MessagePadding.unpad(packet.toBinaryData()!!).size)
            val fragments = manager.createFragments(packet)
            assertEquals(2, fragments.size)
            val sizes = fragments.map { FragmentPayload.decode(it.payload)!!.data.size }
            assertEquals(listOf(469, 44), sizes)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `one megabyte file packet is fragmented with full byte coverage`() {
        val manager = FragmentManager()
        try {
            val content = ByteArray(ONE_MB.toInt()).also { Random(11).nextBytes(it) }
            val filePacket = BitchatFilePacket(
                fileName = "big.bin",
                fileSize = ONE_MB,
                mimeType = "application/octet-stream",
                content = content
            )
            val packet = BitchatPacket(
                version = 2u,
                type = MessageType.FILE_TRANSFER.value,
                senderID = hexToBytes(SENDER_PEER_ID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = 1_700_000_000_000uL,
                payload = filePacket.encode()!!,
                signature = ByteArray(64) { 0x11 },
                ttl = 7u.toUByte()
            )
            val encoded = packet.toBinaryData()!!
            // Frames above the largest padding block are never padded, so the fragment
            // payloads must cover every encoded byte exactly.
            assertTrue(encoded.size > MessagePadding.MAX_BLOCK_SIZE)

            val fragments = manager.createFragments(packet)
            val expectedCount = (encoded.size + 468) / 469
            assertEquals(expectedCount, fragments.size)
            assertEquals(
                encoded.size,
                fragments.sumOf { FragmentPayload.decode(it.payload)!!.data.size }
            )
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `large fragmented packet keeps trailing signature bytes`() {
        val manager = FragmentManager()
        try {
            val payload = ByteArray(3000).also { Random(7).nextBytes(it) }
            // Trailing signature bytes look like PKCS#7 padding (0x02, 0x02); they must not be stripped.
            val signature = ByteArray(64) { 0x02 }
            val packet = signedPacket(payload = payload, signature = signature)

            val fragments = manager.createFragments(packet)
            assertTrue(fragments.size > 1)

            var reassembled: BitchatPacket? = null
            fragments.forEach { manager.handleFragment(it)?.let { result -> reassembled = result } }

            assertNotNull(reassembled)
            assertTrue(reassembled!!.payload.contentEquals(payload))
            assertTrue(reassembled!!.signature!!.contentEquals(signature))
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `incomplete fragment set emits nothing and is cleaned up`() {
        val manager = FragmentManager()
        try {
            val packet = signedPacket(payloadSize = 4200)
            val fragments = manager.createFragments(packet)
            assertTrue(fragments.size > 1)

            fragments.dropLast(1).forEach { assertNull(manager.handleFragment(it)) }
            assertTrue(manager.getDebugInfo().contains("Active Fragment Sets: 1"))

            manager.clearAllFragments()
            assertTrue(manager.getDebugInfo().contains("Active Fragment Sets: 0"))
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `completed reassembly releases its fragment set`() {
        val manager = FragmentManager()
        try {
            val packet = signedPacket(payloadSize = 4200)
            val fragments = manager.createFragments(packet)

            var reassembled: BitchatPacket? = null
            fragments.forEach { manager.handleFragment(it)?.let { result -> reassembled = result } }

            assertNotNull(reassembled)
            assertTrue(reassembled!!.payload.contentEquals(packet.payload))
            assertTrue(manager.getDebugInfo().contains("Active Fragment Sets: 0"))
        } finally {
            manager.shutdown()
        }
    }

    private fun signedPacket(
        payloadSize: Int = 3000,
        signature: ByteArray = ByteArray(64) { (it % 251 + 1).toByte() },
        payload: ByteArray = ByteArray(payloadSize) { (it % 256).toByte() }
    ): BitchatPacket {
        return BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToBytes(SENDER_PEER_ID),
            recipientID = null,
            timestamp = 1_700_000_000_000uL,
            payload = payload,
            signature = signature,
            ttl = 7u.toUByte()
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
