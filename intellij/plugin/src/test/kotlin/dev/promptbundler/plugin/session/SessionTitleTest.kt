package dev.promptbundler.plugin.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTitleTest {
    @Test
    fun shortRequestIsKeptVerbatim() {
        assertEquals("Fix the login bug", SessionTitle.fromUserRequest("Fix the login bug"))
    }

    @Test
    fun whitespaceIsCollapsedAndTrimmed() {
        assertEquals("Fix the login bug", SessionTitle.fromUserRequest("  Fix   the\n login\tbug  "))
    }

    @Test
    fun blankRequestFallsBackToPlaceholder() {
        assertEquals(SessionTitle.FALLBACK, SessionTitle.fromUserRequest("   \n\t "))
    }

    @Test
    fun moreWordsThanLimitAreTruncatedWithEllipsis() {
        // 12 short words: cut at MAX_WORDS (8), so the 9th word onward is dropped.
        val title = SessionTitle.fromUserRequest("one two three four five six seven eight nine ten eleven twelve")

        assertEquals("one two three four five six seven eight...", title)
    }

    @Test
    fun longWordsAreCappedByCharacters() {
        // Few words but very long: the character cap kicks in before the word limit.
        val request = "supercalifragilistic expialidocious extravaganza tremendousness"
        val title = SessionTitle.fromUserRequest(request)

        assertTrue("expected ellipsis, got: $title", title.endsWith("..."))
        assertTrue("title too wide: $title", title.removeSuffix("...").length <= SessionTitle.MAX_CHARS)
    }

    @Test
    fun singleWordLongerThanCapIsTruncated() {
        val word = "a".repeat(SessionTitle.MAX_CHARS + 20)
        val title = SessionTitle.fromUserRequest(word)

        assertEquals("a".repeat(SessionTitle.MAX_CHARS) + "...", title)
    }
}
