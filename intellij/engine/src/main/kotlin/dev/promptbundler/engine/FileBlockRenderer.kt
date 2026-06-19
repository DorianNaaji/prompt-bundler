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
            // Sort by path, then by snippet start so two items sharing a path stay reproducible.
            .sortedWith(compareBy({ it.relativePath }, { it.lines?.first ?: -1 }))
            .joinToString(separator = "\n\n") { renderBlock(it) }

    private fun renderBlock(item: ContextItem): String =
        buildString {
            append("<file path=\"").append(item.relativePath).append('"')
            item.lines?.let {
                append(" lines=\"")
                    .append(it.first)
                    .append('-')
                    .append(it.last)
                    .append('"')
            }
            append(">\n")
            append(item.content)
            if (item.content.isNotEmpty() && !item.content.endsWith("\n")) {
                append('\n')
            }
            append("</file>")
        }
}
