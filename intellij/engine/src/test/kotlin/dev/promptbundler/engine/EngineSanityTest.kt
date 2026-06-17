package dev.promptbundler.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Sanity test proving the engine test chain runs. Real coverage lands in M1.
 */
class EngineSanityTest {
    @Test
    fun `test toolchain executes`() {
        assertEquals(2, 1 + 1)
    }

    @Test
    fun `engine module exposes its name`() {
        assertTrue(Engine.NAME.isNotBlank())
    }
}
