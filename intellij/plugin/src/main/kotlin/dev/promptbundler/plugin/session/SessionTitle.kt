package dev.promptbundler.plugin.session

/**
 * Derives a short, stable session title from the first user request of a session. There is no
 * local model to summarize, so the title is simply the opening words of the request, trimmed to
 * a readable length for a narrow tool window column.
 *
 * The cut is hybrid: at most [MAX_WORDS] words, but also never wider than [MAX_CHARS] characters
 * (a single long word, or many short ones, would otherwise blow past the column). When anything
 * is dropped the result ends with an ellipsis.
 */
object SessionTitle {
    const val MAX_WORDS = 8
    const val MAX_CHARS = 48
    const val FALLBACK = "Untitled session"

    private val WHITESPACE = Regex("\\s+")

    fun fromUserRequest(request: String): String {
        val normalized = request.trim().replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return FALLBACK

        val words = normalized.split(' ')
        val byWords = words.take(MAX_WORDS).joinToString(" ")
        val truncatedByWords = words.size > MAX_WORDS

        val capped = if (byWords.length > MAX_CHARS) byWords.take(MAX_CHARS).trimEnd() else byWords
        val truncatedByChars = byWords.length > MAX_CHARS

        return if (truncatedByWords || truncatedByChars) "$capped..." else capped
    }
}
