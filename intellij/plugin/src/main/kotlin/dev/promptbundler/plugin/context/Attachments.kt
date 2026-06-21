package dev.promptbundler.plugin.context

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.promptbundler.engine.ContentInspector
import dev.promptbundler.engine.ContextItem

/** Outcome of collecting files: the ready-to-attach [items] and how many were [skipped]. */
data class CollectResult(
    val items: List<ContextItem>,
    val skipped: Int,
)

/**
 * Turns IDE files and editor selections into engine [ContextItem]s.
 *
 * The engine never filters what it is given, so the eligibility rules (skip excluded,
 * ignored, hidden, binary and oversized files) live here. [ContentInspector] from the engine
 * provides the binary and size heuristics, keeping a single source of truth.
 */
object Attachments {
    /** Project-relative path with `/` separators; falls back to the file name if outside. */
    fun relativePath(
        project: Project,
        file: VirtualFile,
    ): String {
        val base = project.guessProjectDir()
        return base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
    }

    /** Builds a selection snippet: the file path plus a 1-based, inclusive line range. */
    fun snippet(
        project: Project,
        file: VirtualFile,
        text: String,
        lines: IntRange,
    ): ContextItem = ContextItem(relativePath(project, file), text, lines)

    /** Maximum number of characters captured from a console before truncation kicks in. */
    const val MAX_CONSOLE_CHARS = 20_000

    /**
     * Builds a path-less snippet from captured console [text], identified by [label] and
     * truncated past [MAX_CONSOLE_CHARS] with an explicit marker rather than failing. The
     * tail is kept (test runners surface failures at the end), so the most relevant part of a
     * huge console survives without producing an oversized prompt.
     */
    fun consoleSnippet(
        text: String,
        label: String,
    ): ContextItem = ContextItem(content = truncateConsole(text), label = label)

    private fun truncateConsole(text: String): String {
        if (text.length <= MAX_CONSOLE_CHARS) return text
        val omitted = text.length - MAX_CONSOLE_CHARS
        return "[... truncated $omitted characters ...]\n" + text.substring(text.length - MAX_CONSOLE_CHARS)
    }

    /**
     * Recursively collects attachable files from [roots] (files or directories). A directory
     * contributes all eligible descendants; ineligible files (excluded, ignored, hidden,
     * binary, oversized) are counted as [CollectResult.skipped].
     */
    fun collect(
        project: Project,
        roots: Array<VirtualFile>,
    ): CollectResult {
        val index = ProjectFileIndex.getInstance(project)
        val items = ArrayList<ContextItem>()
        var skipped = 0

        fun visitFile(file: VirtualFile) {
            when {
                !isEligible(file, index) -> skipped++
                else -> {
                    val item = readItem(project, file)
                    if (item != null) items.add(item) else skipped++
                }
            }
        }

        for (root in roots) {
            if (root.isDirectory) {
                VfsUtilCore.iterateChildrenRecursively(
                    root,
                    { dir -> !dir.isDirectory || canDescend(dir, index) },
                    { file ->
                        if (!file.isDirectory) visitFile(file)
                        true
                    },
                )
            } else {
                visitFile(root)
            }
        }
        return CollectResult(items, skipped)
    }

    private fun canDescend(
        dir: VirtualFile,
        index: ProjectFileIndex,
    ): Boolean = !dir.name.startsWith(".") && !index.isExcluded(dir) && !FileTypeManager.getInstance().isFileIgnored(dir)

    private fun isEligible(
        file: VirtualFile,
        index: ProjectFileIndex,
    ): Boolean =
        file.isValid &&
            !file.isDirectory &&
            !file.name.startsWith(".") &&
            !index.isExcluded(file) &&
            !FileTypeManager.getInstance().isFileIgnored(file) &&
            !file.fileType.isBinary &&
            !ContentInspector.isOversized(file.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

    private fun readItem(
        project: Project,
        file: VirtualFile,
    ): ContextItem? {
        val bytes = file.contentsToByteArray()
        if (ContentInspector.isBinary(bytes)) return null
        return ContextItem(relativePath(project, file), String(bytes, file.charset))
    }
}
