package com.eazychat.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eazychat.app.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Robolectric tests for channel lifecycle: join / leave, membership, unread routing and the
 * password gate that was repaired earlier (see ChannelCryptoTest for the pure crypto cases).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelManagerTest {

    private companion object {
        const val MY_PEER_ID = "AABBCCDDEEFF0011"
        const val PEER_B = "1122334455667788"
    }

    private lateinit var state: ChatState
    private lateinit var dataManager: DataManager
    private lateinit var messageManager: MessageManager
    private lateinit var manager: ChannelManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        state = ChatState()
        dataManager = DataManager(context)
        messageManager = MessageManager(state)
        manager = ChannelManager(state, messageManager, dataManager, CoroutineScope(Dispatchers.Unconfined))
    }

    private fun channelMessage(channel: String = "#room", senderPeerID: String? = PEER_B) = BitchatMessage(
        sender = "bob",
        content = "hello channel",
        timestamp = Date(),
        senderPeerID = senderPeerID,
        channel = channel
    )

    private fun protectChannel(channel: String, password: String) {
        dataManager.saveChannelPasswordCommitment(channel, ChannelCrypto.commitmentFor(password, channel))
        state.setPasswordProtectedChannels(setOf(channel))
    }

    @Test
    fun `join creates the channel and registers creator plus member`() {
        assertTrue(manager.joinChannel("room", null, MY_PEER_ID))

        assertEquals(setOf("#room"), state.getJoinedChannelsValue())
        assertEquals("#room", state.getCurrentChannelValue())
        assertTrue(dataManager.isChannelCreator("#room", MY_PEER_ID))
        assertEquals(setOf(MY_PEER_ID), dataManager.channelMembers["#room"])
        assertTrue(state.getChannelMessagesValue().containsKey("#room"))
    }

    @Test
    fun `join is idempotent for an already joined channel`() {
        manager.joinChannel("room", null, MY_PEER_ID)
        manager.switchToChannel(null)

        assertTrue(manager.joinChannel("#room", null, MY_PEER_ID))

        assertEquals(setOf("#room"), state.getJoinedChannelsValue())
        assertEquals("#room", state.getCurrentChannelValue())
    }

    @Test
    fun `protected channel rejects a wrong password`() {
        protectChannel("#room", "secret")

        assertFalse(manager.joinChannel("#room", "wrong", MY_PEER_ID))

        assertTrue(state.getJoinedChannelsValue().isEmpty())
        assertFalse(manager.hasChannelKey("#room"))
        assertFalse(state.getShowPasswordPromptValue())
    }

    @Test
    fun `protected channel accepts the matching password and caches the key`() {
        protectChannel("#room", "secret")

        assertTrue(manager.joinChannel("#room", "secret", MY_PEER_ID))

        assertEquals(setOf("#room"), state.getJoinedChannelsValue())
        assertTrue(manager.hasChannelKey("#room"))
        assertEquals("secret", manager.getChannelPassword("#room"))
    }

    @Test
    fun `protected channel asks for a password when none is supplied`() {
        protectChannel("#room", "secret")

        assertFalse(manager.joinChannel("#room", null, MY_PEER_ID))

        assertTrue(state.getShowPasswordPromptValue())
        assertEquals("#room", state.getPasswordPromptChannelValue())
        assertTrue(state.getJoinedChannelsValue().isEmpty())
    }

    @Test
    fun `channel creator can enter the protected channel without the password`() {
        protectChannel("#room", "secret")
        dataManager.addChannelCreator("#room", MY_PEER_ID)

        assertTrue(manager.joinChannel("#room", null, MY_PEER_ID))

        assertEquals(setOf("#room"), state.getJoinedChannelsValue())
        assertFalse(manager.hasChannelKey("#room"))
    }

    @Test
    fun `leave clears channel state, messages, members and stored password`() {
        manager.joinChannel("room", null, MY_PEER_ID)
        manager.setChannelPassword("#room", "secret")
        manager.addChannelMessage("#room", channelMessage(), PEER_B)
        assertNotNull(dataManager.channelMembers["#room"]?.contains(PEER_B))

        manager.leaveChannel("#room")

        assertTrue(state.getJoinedChannelsValue().isEmpty())
        assertNull(state.getCurrentChannelValue())
        assertFalse(state.getChannelMessagesValue().containsKey("#room"))
        assertFalse(state.getUnreadChannelMessagesValue().containsKey("#room"))
        assertNull(dataManager.channelMembers["#room"])
        assertFalse(dataManager.channelCreators.containsKey("#room"))
        assertNull(dataManager.loadChannelPasswordCommitment("#room"))
        assertFalse(manager.hasChannelKey("#room"))
    }

    @Test
    fun `incoming channel messages track senders as members`() {
        manager.joinChannel("room", null, MY_PEER_ID)

        manager.addChannelMessage("#room", channelMessage(), PEER_B)

        assertEquals(setOf(MY_PEER_ID, PEER_B), dataManager.channelMembers["#room"])
    }

    @Test
    fun `disconnected members are pruned but the local peer is kept`() {
        manager.joinChannel("room", null, MY_PEER_ID)
        manager.addChannelMessage("#room", channelMessage(), PEER_B)

        manager.cleanupDisconnectedMembers(listOf(MY_PEER_ID), MY_PEER_ID)

        assertEquals(setOf(MY_PEER_ID), dataManager.channelMembers["#room"])
    }

    @Test
    fun `channel unread count only grows when the channel is not being viewed`() {
        manager.joinChannel("room", null, MY_PEER_ID)
        manager.addChannelMessage("#room", channelMessage(), PEER_B)
        assertNull(state.getUnreadChannelMessagesValue()["#room"])

        manager.switchToChannel(null)
        manager.addChannelMessage("#room", channelMessage(), PEER_B)
        assertEquals(1, state.getUnreadChannelMessagesValue()["#room"])

        manager.switchToChannel("#room")
        assertFalse(state.getUnreadChannelMessagesValue().containsKey("#room"))
    }

    @Test
    fun `switching channels closes the private chat selection`() {
        manager.joinChannel("room", null, MY_PEER_ID)
        state.setSelectedPrivateChatPeer(PEER_B)

        manager.switchToChannel("#room")

        assertNull(state.getSelectedPrivateChatPeerValue())
        assertEquals("#room", state.getCurrentChannelValue())
    }
}
