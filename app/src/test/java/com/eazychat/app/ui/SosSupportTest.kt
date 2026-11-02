package com.eazychat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosSupportTest {

    @Test
    fun `sos without note uses the bare marker`() {
        assertEquals(SosSupport.PREFIX, SosSupport.buildContent(null))
        assertEquals(SosSupport.PREFIX, SosSupport.buildContent("   "))
    }

    @Test
    fun `sos carries the optional note`() {
        assertEquals("${SosSupport.PREFIX}：3 楼楼梯间", SosSupport.buildContent(" 3 楼楼梯间 "))
        assertEquals("3 楼楼梯间", SosSupport.noteOf("${SosSupport.PREFIX}：3 楼楼梯间"))
    }

    @Test
    fun `plain messages are not treated as sos`() {
        assertFalse(SosSupport.isSos("hello"))
        assertNull(SosSupport.noteOf("hello"))
    }

    @Test
    fun `receipt round trip keeps the message id`() {
        val ack = SosSupport.buildAckContent("ABC-123")
        assertEquals("ABC-123", SosSupport.parseAck(ack))
        assertNull(SosSupport.parseAck("hello"))
    }
}
