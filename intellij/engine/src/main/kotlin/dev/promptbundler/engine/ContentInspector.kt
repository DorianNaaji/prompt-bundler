package dev.promptbundler.engine

/**
 * Lightweight, pure guards used to decide whether a candidate file should be attached.
 *
 * The engine never applies these itself: it assembles whatever items it is given. The
 * inspector is exposed so the editor integration (M3) can flag binary or oversized files
 * and skip them with visual feedback, rather than embedding garbage into the prompt.
 */
object ContentInspector {
    /** Default size ceiling above which a file is considered too large to attach. */
    const val DEFAULT_MAX_BYTES: Int = 256 * 1024

    /** A file is treated as binary if its bytes contain a NUL, a robust simple heuristic. */
    fun isBinary(bytes: ByteArray): Boolean = bytes.any { it == 0.toByte() }

    /** Text variant of [isBinary], detecting an embedded NUL character. */
    fun isBinary(text: String): Boolean = text.any { it.code == 0 }

    /** Whether [byteCount] exceeds [maxBytes] (defaults to [DEFAULT_MAX_BYTES]). */
    fun isOversized(
        byteCount: Int,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): Boolean = byteCount > maxBytes
}
