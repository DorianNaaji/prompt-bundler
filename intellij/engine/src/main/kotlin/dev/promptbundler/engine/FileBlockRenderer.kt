package dev.promptbundler.engine

/**
 * Renders the `{files}` section: each context item wrapped in an identifiable block.
 *
 * A file-backed item renders as:
 * ```
 * <file path="relative/path">
 * raw content
 * </file>
 * ```
 * A label-backed item (such as a captured console output) has no path and renders as a
 * `<snippet>` block, so its tag never collides with the surrounding `<context_tree>` /
 * `<file_contents>` wrappers:
 * ```
 * <snippet label="Console output (Run: MyTest)">
 * raw content
 * </snippet>
 * ```
 * Attribute values are XML-escaped so a path or label containing `" & < >` cannot break the
 * tag. Content is embedded verbatim; a single trailing newline is ensured before the closing
 * tag. File blocks are emitted first, sorted by [ContextItem.relativePath] (matching the
 * tree order), then the snippet blocks sorted by [ContextItem.label]; blocks are separated by
 * a blank line, so the output is reproducible.
 */
object FileBlockRenderer {
    fun render(items: List<ContextItem>): String =
        items
            // File items first (by path then snippet start), then label items by label, so
            // two items sharing a key stay reproducible.
            .sortedWith(
                compareBy(
                    { it.relativePath == null },
                    { it.relativePath ?: it.label },
                    { it.lines?.first ?: -1 },
                ),
            ).joinToString(separator = "\n\n") { renderBlock(it) }

    private fun renderBlock(item: ContextItem): String =
        buildString {
            if (item.relativePath != null) {
                append("<file path=\"").append(escapeAttr(item.relativePath)).append('"')
                item.lines?.let {
                    append(" lines=\"")
                        .append(it.first)
                        .append('-')
                        .append(it.last)
                        .append('"')
                }
                append(">\n")
            } else {
                append("<snippet label=\"").append(escapeAttr(item.label.orEmpty())).append("\">\n")
            }
            append(item.content)
            if (item.content.isNotEmpty() && !item.content.endsWith("\n")) {
                append('\n')
            }
            append(if (item.relativePath != null) "</file>" else "</snippet>")
        }

    /** Escapes the characters that would break a double-quoted XML attribute value. */
    private fun escapeAttr(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
