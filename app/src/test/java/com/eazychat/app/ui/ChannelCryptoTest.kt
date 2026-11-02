package com.eazychat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the channel password check that used to always return `true`.
 */
class ChannelCryptoTest {

    @Test
    fun `same password and channel derive the same commitment`() {
        assertEquals(
            ChannelCrypto.commitmentFor("secret", "#room"),
            ChannelCrypto.commitmentFor("secret", "#room")
        )
    }

    @Test
    fun `different passwords derive different commitments`() {
        assertNotEquals(
            ChannelCrypto.commitmentFor("secret", "#room"),
            ChannelCrypto.commitmentFor("secret2", "#room")
        )
    }

    @Test
    fun `password is bound to the channel name`() {
        assertNotEquals(
            ChannelCrypto.commitmentFor("secret", "#room"),
            ChannelCrypto.commitmentFor("secret", "#other")
        )
    }

    @Test
    fun `first password is accepted when no commitment was stored yet`() {
        assertTrue(ChannelCrypto.verifies(null, "secret", "#room"))
    }

    @Test
    fun `matching password is accepted`() {
        val stored = ChannelCrypto.commitmentFor("secret", "#room")
        assertTrue(ChannelCrypto.verifies(stored, "secret", "#room"))
    }

    @Test
    fun `wrong password is rejected`() {
        val stored = ChannelCrypto.commitmentFor("secret", "#room")
        assertFalse(ChannelCrypto.verifies(stored, "wrong", "#room"))
    }
}
