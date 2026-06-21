package dev.promptbundler.plugin.support

import com.intellij.ide.util.PropertiesComponent

/**
 * Persisted state for the donation reminder, abstracted behind an interface so the decision logic
 * can be unit-tested without an IDE fixture.
 */
interface DonationStore {
    fun isDismissed(): Boolean

    fun setDismissed(value: Boolean)

    fun isSnoozed(): Boolean

    fun setSnoozed(value: Boolean)

    fun getCount(): Int

    fun setCount(value: Int)
}

/** Real store backed by the IDE's application-level [PropertiesComponent]. */
class PropertiesDonationStore(
    private val props: PropertiesComponent = PropertiesComponent.getInstance(),
) : DonationStore {
    override fun isDismissed() = props.getBoolean(DISMISSED_KEY, false)

    override fun setDismissed(value: Boolean) = props.setValue(DISMISSED_KEY, value)

    override fun isSnoozed() = props.getBoolean(SNOOZED_KEY, false)

    override fun setSnoozed(value: Boolean) = props.setValue(SNOOZED_KEY, value)

    override fun getCount() = props.getInt(COUNT_KEY, 0)

    override fun setCount(value: Int) = props.setValue(COUNT_KEY, value, 0)

    private companion object {
        const val DISMISSED_KEY = "promptbundler.donation.dismissed"
        const val SNOOZED_KEY = "promptbundler.donation.snoozed"
        const val COUNT_KEY = "promptbundler.donation.promptCount"
    }
}

/**
 * Decides when the "buy me a coffee" dialog should pop up. Pure logic, no UI: the panel calls
 * [onPromptSent] once per successful prompt and shows the dialog when it returns true, then routes
 * the user's choice back through [onYes]/[onMaybeLater]/[onDontRemind].
 *
 * The counter resets every time the dialog is shown. The first dialog appears after [firstThreshold]
 * prompts; once the user picks "Maybe Later" the reminder is snoozed and only re-asks after the
 * longer [snoozedThreshold]. "Yes" and "Don't remind me" silence it for good.
 */
class DonationReminder(
    private val store: DonationStore,
    private val firstThreshold: Int = FIRST_THRESHOLD,
    private val snoozedThreshold: Int = SNOOZED_THRESHOLD,
) {
    /** Records one successful prompt. Returns true when the dialog should be shown now. */
    fun onPromptSent(): Boolean {
        if (store.isDismissed()) return false
        val threshold = if (store.isSnoozed()) snoozedThreshold else firstThreshold
        val next = store.getCount() + 1
        if (next >= threshold) {
            store.setCount(0)
            return true
        }
        store.setCount(next)
        return false
    }

    fun onYes() = store.setDismissed(true)

    fun onDontRemind() = store.setDismissed(true)

    fun onMaybeLater() {
        // The counter was reset when the dialog showed; snoozing stretches the next wait to
        // [snoozedThreshold] prompts instead of [firstThreshold].
        store.setSnoozed(true)
    }

    companion object {
        const val FIRST_THRESHOLD = 10
        const val SNOOZED_THRESHOLD = 10
        const val DONATION_URL = "https://buymeacoffee.com/dorian.naaji"
    }
}
