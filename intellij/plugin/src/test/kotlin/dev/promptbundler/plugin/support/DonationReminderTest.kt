package dev.promptbundler.plugin.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonationReminderTest {
    /** In-memory stand-in for the IDE-backed [PropertiesDonationStore]. */
    private class FakeStore(
        private var dismissedFlag: Boolean = false,
        private var snoozedFlag: Boolean = false,
        var storedCount: Int = 0,
    ) : DonationStore {
        val dismissed get() = dismissedFlag

        override fun isDismissed() = dismissedFlag

        override fun setDismissed(value: Boolean) {
            dismissedFlag = value
        }

        override fun isSnoozed() = snoozedFlag

        override fun setSnoozed(value: Boolean) {
            snoozedFlag = value
        }

        override fun getCount() = storedCount

        override fun setCount(value: Int) {
            storedCount = value
        }
    }

    @Test
    fun `dialog shows on the third prompt and not before`() {
        val store = FakeStore()
        val reminder = DonationReminder(store, firstThreshold = 3)

        assertFalse("first prompt", reminder.onPromptSent())
        assertFalse("second prompt", reminder.onPromptSent())
        assertTrue("third prompt", reminder.onPromptSent())
    }

    @Test
    fun `Maybe Later snoozes so the reminder re-asks after the longer threshold`() {
        val store = FakeStore()
        val reminder = DonationReminder(store, firstThreshold = 3, snoozedThreshold = 10)

        repeat(3) { reminder.onPromptSent() }
        reminder.onMaybeLater()
        assertEquals("counter reset when dialog shown", 0, store.storedCount)

        repeat(9) { assertFalse("snoozed: no prompt before the tenth", reminder.onPromptSent()) }
        assertTrue("re-asks after ten more prompts", reminder.onPromptSent())
    }

    @Test
    fun `Yes silences the reminder for good`() {
        val store = FakeStore()
        val reminder = DonationReminder(store, firstThreshold = 3)

        repeat(3) { reminder.onPromptSent() }
        reminder.onYes()

        assertTrue(store.dismissed)
        repeat(10) { assertFalse("never prompts again", reminder.onPromptSent()) }
    }

    @Test
    fun `Don't remind me silences the reminder for good`() {
        val store = FakeStore()
        val reminder = DonationReminder(store, firstThreshold = 3)

        repeat(3) { reminder.onPromptSent() }
        reminder.onDontRemind()

        assertTrue(store.dismissed)
        repeat(10) { assertFalse("never prompts again", reminder.onPromptSent()) }
    }

    @Test
    fun `a dismissed reminder never counts or prompts`() {
        val store = FakeStore(dismissedFlag = true)
        val reminder = DonationReminder(store, firstThreshold = 3)

        repeat(5) { assertFalse(reminder.onPromptSent()) }
        assertEquals("dismissed store stays untouched", 0, store.storedCount)
    }
}
