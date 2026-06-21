package dev.promptbundler.plugin.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.promptbundler.engine.ContextItem
import java.util.concurrent.atomic.AtomicLong

/** A single attached context entry: the engine [item] plus UI metadata for its chip. */
data class Attachment(
    val id: Long,
    val label: String,
    val item: ContextItem,
)

/** Listener notified whenever the live context changes (add, remove, clear). */
fun interface ContextBundleListener {
    fun onChanged()
}

/**
 * Holds the live, ephemeral context for a project: the files and snippets the user attached
 * for the next meta-prompt.
 *
 * This is deliberately NOT a [com.intellij.openapi.components.PersistentStateComponent]: a
 * closed IDE means a finished work session, so the attached context is meant to be lost on
 * shutdown (see the kickstart persistence model). The chat panel observes [TOPIC] to rebuild
 * its chips, and [PromptChatController] reads [items] when assembling the prompt.
 */
@Service(Service.Level.PROJECT)
class ContextBundleService(
    private val project: Project,
) {
    // Keyed by a dedup key (path + optional line range) so the same file or snippet is never
    // attached twice; LinkedHashMap preserves attachment order for a stable chips row.
    private val attachments = LinkedHashMap<String, Attachment>()
    private val ids = AtomicLong()

    /** Engine items in attachment order, for assembly. */
    val items: List<ContextItem>
        get() = synchronized(attachments) { attachments.values.map { it.item } }

    /** Current attachments in attachment order, for the chips row. */
    fun snapshot(): List<Attachment> = synchronized(attachments) { attachments.values.toList() }

    /**
     * Adds [newItems], skipping any whose (path + line range) key is already attached.
     * Returns how many were actually added, and fires [TOPIC] once if anything changed.
     */
    fun add(newItems: List<ContextItem>): Int {
        if (newItems.isEmpty()) return 0
        var added = 0
        synchronized(attachments) {
            for (item in newItems) {
                val key = keyOf(item)
                if (attachments.containsKey(key)) continue
                attachments[key] = Attachment(ids.incrementAndGet(), labelOf(item), item)
                added++
            }
        }
        if (added > 0) publish()
        return added
    }

    /** Removes the attachment with the given [id], if present, and notifies listeners. */
    fun remove(id: Long) {
        val removed =
            synchronized(attachments) {
                val key = attachments.entries.firstOrNull { it.value.id == id }?.key
                if (key != null) attachments.remove(key) != null else false
            }
        if (removed) publish()
    }

    /** Drops all attachments and notifies listeners when the context was not already empty. */
    fun clear() {
        val cleared = synchronized(attachments) { attachments.isNotEmpty().also { attachments.clear() } }
        if (cleared) publish()
    }

    private fun publish() = project.messageBus.syncPublisher(TOPIC).onChanged()

    // Label-backed snippets (console output) key on label + content so an unchanged re-capture
    // dedups, while re-running and attaching a different output adds a fresh chip.
    private fun keyOf(item: ContextItem): String {
        val path = item.relativePath ?: return "label:${item.label}:${item.content.hashCode()}"
        return item.lines?.let { "$path#${it.first}-${it.last}" } ?: path
    }

    private fun labelOf(item: ContextItem): String {
        val path = item.relativePath ?: return item.label ?: "Snippet"
        val name = path.substringAfterLast('/')
        return item.lines?.let { "$name:${it.first}-${it.last}" } ?: name
    }

    companion object {
        @JvmField
        val TOPIC: Topic<ContextBundleListener> = Topic.create("PromptBundler context", ContextBundleListener::class.java)

        fun getInstance(project: Project): ContextBundleService = project.service()
    }
}
