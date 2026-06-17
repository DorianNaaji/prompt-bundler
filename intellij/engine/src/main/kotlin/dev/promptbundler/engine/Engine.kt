package dev.promptbundler.engine

/**
 * Marker for the PromptBundler assembly engine.
 *
 * This module hosts the deterministic, platform-independent logic (prompt assembly,
 * token estimation, template handling). It is intentionally empty in M0; the real
 * engine arrives in M1.
 */
object Engine {
    /** Human-readable name of the engine module. */
    const val NAME: String = "PromptBundler engine"
}
