package com.eazychat.app.ui

/**
 * Encoding for the one-tap SOS broadcast and its delivery receipt.
 *
 * SOS travels as a normal broadcast chat message (so it reuses flooding, TTL and dedup),
 * but the sender's note is prefixed with a visible marker; the receipt uses an invisible
 * control-character prefix so it never shows up in the chat timeline.
 */
internal object SosSupport {

    const val PREFIX = "🆘 SOS"
    private const val ACK_PREFIX = "\u0001SOS_ACK\u0001"

    fun buildContent(note: String?): String {
        val trimmed = note?.trim().orEmpty()
        return if (trimmed.isEmpty()) PREFIX else "$PREFIX：$trimmed"
    }

    fun isSos(content: String): Boolean = content.startsWith(PREFIX)

    /** Returns the optional note carried by an SOS message, or null when none was written. */
    fun noteOf(content: String): String? {
        if (!isSos(content)) return null
        val rest = content.removePrefix(PREFIX)
        return rest.trim().removePrefix("：").trim().ifEmpty { null }
    }

    fun buildAckContent(messageId: String): String = ACK_PREFIX + messageId

    /** Returns the acknowledged SOS message id, or null if this content is not a receipt. */
    fun parseAck(content: String): String? =
        if (content.startsWith(ACK_PREFIX)) content.removePrefix(ACK_PREFIX).trim().ifEmpty { null } else null
}
