package dev.promptbundler.engine

/**
 * Renders the `{files}` section: each context item wrapped in a `<file>` block.
 *
 * A block has the shape:
 * ```
 * <file path="relative/path">
 * raw content
 * </file>
 * ```
 * Content is embedded verbatim; a single trailing newline is ensured before the closing
 * tag. Items are emitted sorted by [ContextItem.relativePath] (matching the tree order),
 * and blocks are separated by a blank line, so the output is reproducible.
 */
object FileBlockRenderer {
    fun render(items: List<ContextItem>): String =
        items
            .sortedBy { it.relativePath }
            .joinToString(separator = "\n\n") { renderBlock(it) }

    private fun renderBlock(item: ContextItem): String =
        buildString {
            append("<file path=\"").append(item.relativePath).append("\">\n")
            append(item.content)
            if (item.content.isNotEmpty() && !item.content.endsWith("\n")) {
                append('\n')
            }
            append("</file>")
        }
}
