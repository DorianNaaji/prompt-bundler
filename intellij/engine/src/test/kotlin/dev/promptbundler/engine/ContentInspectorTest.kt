package dev.promptbundler.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentInspectorTest {
    @Test
    fun `bytes with a NUL are binary`() {
        assertTrue(ContentInspector.isBinary(byteArrayOf(0x48, 0x00, 0x49)))
        assertFalse(ContentInspector.isBinary("hello world".toByteArray()))
    }

    @Test
    fun `text with a NUL is binary`() {
        assertTrue(ContentInspector.isBinary("a" + 0.toChar() + "b"))
        assertFalse(ContentInspector.isBinary("plain text"))
    }

    @Test
    fun `oversized is decided against the byte ceiling`() {
        assertFalse(ContentInspector.isOversized(ContentInspector.DEFAULT_MAX_BYTES))
        assertTrue(ContentInspector.isOversized(ContentInspector.DEFAULT_MAX_BYTES + 1))
        assertTrue(ContentInspector.isOversized(11, maxBytes = 10))
    }
}
