package com.eazychat.app.services

import android.content.Context
import android.util.Log
import com.eazychat.app.mesh.BluetoothMeshService
import com.eazychat.app.model.ReadReceipt

/**
 * Routes messages between BLE mesh transports, matching iOS behavior.
 */
class MessageRouter private constructor(
    private val context: Context,
    private val mesh: BluetoothMeshService
) {
    companion object {
        private const val TAG = "MessageRouter"
        @Volatile private var INSTANCE: MessageRouter? = null
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: BluetoothMeshService): MessageRouter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.also {
                    return it
                }
                MessageRouter(context.applicationContext, mesh).also {
                    INSTANCE = it
                }
            }
        }
    }

    // Outbox: peerID -> queued message; de-duplicated by messageID and thread-safe
    private val outbox = OfflineMessageQueue()

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        val hasMesh = mesh.getPeerInfo(toPeerID)?.isConnected == true
        val hasEstablished = mesh.hasEstablishedSession(toPeerID)
        if (hasMesh && hasEstablished) {
            Log.d(TAG, "Routing PM via mesh to ${toPeerID} msg_id=${messageID.take(8)}…")
            mesh.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
        } else {
            if (outbox.enqueue(toPeerID, content, recipientNickname, messageID)) {
                Log.d(TAG, "Queued PM for ${toPeerID} (no mesh connection) msg_id=${messageID.take(8)}…")
            } else {
                Log.d(TAG, "PM already queued for ${toPeerID} msg_id=${messageID.take(8)}…, skipping duplicate")
            }
            Log.d(TAG, "Initiating noise handshake after queueing PM for ${toPeerID.take(8)}…")
            mesh.initiateNoiseHandshake(toPeerID)
        }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        if ((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID)) {
            Log.d(TAG, "Routing READ via mesh to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            mesh.sendReadReceipt(receipt.originalMessageID, toPeerID, mesh.getPeerNicknames()[toPeerID] ?: mesh.myPeerID)
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        if (mesh.getPeerInfo(toPeerID)?.isConnected == true) {
            val content = if (isFavorite) "[FAVORITED]" else "[UNFAVORITED]"
            val nickname = mesh.getPeerNicknames()[toPeerID] ?: toPeerID
            mesh.sendPrivateMessage(content, toPeerID, nickname)
        }
    }

    // Flush any queued messages for a specific peerID
    fun flushOutboxFor(peerID: String) {
        val queued = outbox.peek(peerID)
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing outbox for ${peerID.take(8)}… count=${queued.size}")
        for (entry in queued) {
            var hasMesh = mesh.getPeerInfo(peerID)?.isConnected == true && mesh.hasEstablishedSession(peerID)
            // If this is a noiseHex key, see if there is a connected mesh peer for this identity
            if (!hasMesh && peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val meshPeer = resolveMeshPeerForNoiseHex(peerID)
                if (meshPeer != null && mesh.getPeerInfo(meshPeer)?.isConnected == true && mesh.hasEstablishedSession(meshPeer)) {
                    mesh.sendPrivateMessage(entry.content, meshPeer, entry.nickname, entry.messageID)
                    outbox.remove(peerID, entry.messageID)
                    continue
                }
            }
            if (hasMesh) {
                mesh.sendPrivateMessage(entry.content, peerID, entry.nickname, entry.messageID)
                outbox.remove(peerID, entry.messageID)
            }
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.snapshot().keys.forEach { flushOutboxFor(it) }
    }

    private fun resolveMeshPeerForNoiseHex(noiseHex: String): String? {
        return try {
            mesh.getPeerNicknames().keys.firstOrNull { pid ->
                val info = mesh.getPeerInfo(pid)
                val keyHex = info?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
                keyHex != null && keyHex.equals(noiseHex, ignoreCase = true)
            }
        } catch (_: Exception) { null }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { null }
            noiseHex?.let { flushOutboxFor(it) }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
        noiseHex?.let { flushOutboxFor(it) }
    }
}
