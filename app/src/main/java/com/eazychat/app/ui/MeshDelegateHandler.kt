package com.eazychat.app.ui

import com.eazychat.app.mesh.BluetoothMeshDelegate
import com.eazychat.app.ui.NotificationTextUtils
import com.eazychat.app.mesh.BluetoothMeshService
import com.eazychat.app.model.BitchatMessage
import com.eazychat.app.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Handles all BluetoothMeshDelegate callbacks and routes them to appropriate managers
 */
class MeshDelegateHandler(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val coroutineScope: CoroutineScope,
    private val onHapticFeedback: () -> Unit,
    private val getMyPeerID: () -> String,
    private val getMeshService: () -> BluetoothMeshService
) : BluetoothMeshDelegate {

    override fun didReceiveMessage(message: BitchatMessage) {
        coroutineScope.launch {
            // FIXED: Deduplicate messages from dual connection paths
            val messageKey = messageManager.generateMessageKey(message)
            if (messageManager.isMessageProcessed(messageKey)) {
                return@launch // Duplicate message, ignore
            }
            messageManager.markMessageProcessed(messageKey)
            
            // Check if sender is blocked
            message.senderPeerID?.let { senderPeerID ->
                if (privateChatManager.isPeerBlocked(senderPeerID)) {
                    return@launch
                }
            }
            
            // Trigger haptic feedback
            onHapticFeedback()

            // SOS broadcast: always alert loudly and send a receipt back to the sender
            if (SosSupport.isSos(message.content)) {
                if (message.senderPeerID == getMyPeerID()) {
                    return@launch
                }
                messageManager.addMessage(message)
                notificationManager.showSosNotification(
                    senderNickname = message.sender,
                    note = SosSupport.noteOf(message.content),
                    messageId = message.id
                )
                getMeshService().sendMessage(
                    SosSupport.buildAckContent(message.id),
                    emptyList(),
                    null
                )
                return@launch
            }

            // SOS delivery receipt: update the original message, never show the receipt itself
            SosSupport.parseAck(message.content)?.let { sosMessageId ->
                messageManager.updateMessageDeliveryStatus(
                    sosMessageId,
                    DeliveryStatus.Delivered(message.sender, Date())
                )
                return@launch
            }

            if (message.isPrivate) {
                // Private message
                privateChatManager.handleIncomingPrivateMessage(message)
                
                // Reactive read receipts: Send immediately if user is currently viewing this chat
                message.senderPeerID?.let { senderPeerID ->
                    sendReadReceiptIfFocused(senderPeerID)
                }
                
                // Show notification with enhanced information - now includes senderPeerID 
                message.senderPeerID?.let { senderPeerID ->
                    // Use nickname if available, fall back to sender or senderPeerID
                    val senderNickname = message.sender.takeIf { it != senderPeerID } ?: senderPeerID
                    val preview = NotificationTextUtils.buildPrivateMessagePreview(message)
                    notificationManager.showPrivateMessageNotification(
                        senderPeerID = senderPeerID,
                        senderNickname = senderNickname,
                        messageContent = preview
                    )
                }
            } else if (message.channel != null) {
                // Channel message
                if (state.getJoinedChannelsValue().contains(message.channel)) {
                    channelManager.addChannelMessage(message.channel, message, message.senderPeerID)
                }
            } else {
                // Public mesh message - always store to preserve message history
                messageManager.addMessage(message)

                // Check for mentions in mesh chat
                checkAndTriggerMeshMentionNotification(message)
            }
            
            // Periodic cleanup
            if (messageManager.isMessageProcessed("cleanup_check_${System.currentTimeMillis()/30000}")) {
                messageManager.cleanupDeduplicationCaches()
            }
        }
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        coroutineScope.launch {
            state.setConnectedPeers(peers)
            state.setIsConnected(peers.isNotEmpty())
            notificationManager.showActiveUserNotification(peers)
            // Flush router outbox for any peers that just connected
            runCatching { com.eazychat.app.services.MessageRouter.tryGetInstance()?.onPeersUpdated(peers) }

            // Clean up channel members who disconnected
            channelManager.cleanupDisconnectedMembers(peers, getMyPeerID())

            // Clean up disconnected private chats
            state.getSelectedPrivateChatPeerValue()?.let { currentPeer ->
                val isMeshEphemeral = currentPeer.length == 16 && currentPeer.matches(Regex("^[0-9a-fA-F]+$"))
                if (isMeshEphemeral && !peers.contains(currentPeer)) {
                    privateChatManager.cleanupDisconnectedPeer(currentPeer)
                }
            }
        }
    }

    /**
     * Merge any chats stored under the given keys into the connected peer's chat entry.
     */
    private fun unifyChatsIntoPeer(targetPeerID: String, keysToMerge: List<String>) {
        com.eazychat.app.services.ConversationAliasResolver.unifyChatsIntoPeer(state, targetPeerID, keysToMerge)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        coroutineScope.launch {
            channelManager.removeChannelMember(channel, fromPeer)
        }
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Delivered(recipientPeerID, Date()))
        }
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Read(recipientPeerID, Date()))
        }
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return channelManager.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? = state.getNicknameValue()
    
    override fun isFavorite(peerID: String): Boolean {
        return privateChatManager.isFavorite(peerID)
    }
    
    /**
     * Check for mentions in mesh messages and trigger notifications
     */
    private fun checkAndTriggerMeshMentionNotification(message: BitchatMessage) {
        try {
            // Get user's current nickname
            val currentNickname = state.getNicknameValue()
            if (currentNickname.isNullOrEmpty()) {
                return
            }

            // Check if this message mentions the current user using @username format
            val isMention = checkForMeshMention(message.content, currentNickname)

            if (isMention) {
                android.util.Log.d("MeshDelegateHandler", "🔔 Triggering mesh mention notification from ${message.sender}")

                notificationManager.showMeshMentionNotification(
                    senderNickname = message.sender,
                    messageContent = message.content,
                    senderPeerID = message.senderPeerID
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshDelegateHandler", "Error checking mesh mentions: ${e.message}")
        }
    }

    /**
     * Check if the content mentions the current user with @username format (simple, no hash suffix)
     */
    private fun checkForMeshMention(content: String, currentNickname: String): Boolean {
        // Simple mention pattern for mesh: @username (no hash suffix like geohash)
        val mentionPattern = "@([\\p{L}0-9_]+)".toRegex()

        return mentionPattern.findAll(content).any { match ->
            val mentionedUsername = match.groupValues[1]
            // Direct comparison for mesh mentions (no hash suffix to remove)
            mentionedUsername.equals(currentNickname, ignoreCase = true)
        }
    }

    /**
     * Send read receipts reactively based on UI focus state.
     * Uses same logic as notification system - send read receipt if user is currently
     * viewing the private chat with this sender AND app is in foreground.
     */
    private fun sendReadReceiptIfFocused(senderPeerID: String) {
        // Get notification manager's focus state (mirror the notification logic)
        val isAppInBackground = notificationManager.getAppBackgroundState()
        val currentPrivateChatPeer = notificationManager.getCurrentPrivateChatPeer()
        
        // Send read receipt if user is currently focused on this specific chat
        val shouldSendReadReceipt = !isAppInBackground && currentPrivateChatPeer == senderPeerID
        
        if (shouldSendReadReceipt) {
            android.util.Log.d("MeshDelegateHandler", "Sending reactive read receipt for focused chat with $senderPeerID")
            privateChatManager.sendReadReceiptsForPeer(senderPeerID, getMeshService())
        } else {
            android.util.Log.d("MeshDelegateHandler", "Skipping read receipt - chat not focused (background: $isAppInBackground, current peer: $currentPrivateChatPeer, sender: $senderPeerID)")
        }
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager

    /**
     * Expose mesh peer info for components that need to resolve identities (e.g., Nostr mapping)
     */
    fun getPeerInfo(peerID: String): com.eazychat.app.mesh.PeerInfo? {
        return getMeshService().getPeerInfo(peerID)
    }

}
