package dev.promptbundler.engine

/**
 * Assembles a [BundleRequest] into the final meta-prompt.
 *
 * This is the public entry point of the engine. It is pure and deterministic: the same
 * request always yields the same [BundleResult], independent of item order. The assembler
 * substitutes the `{tree}`, `{files}` and `{query}` placeholders of the template in a
 * single pass, so substituted content that itself contains a placeholder token is never
 * re-expanded.
 */
object PromptAssembler {
    private val PLACEHOLDER = Regex("""\{(tree|files|query)}""")

    // Matches an XML block whose body is only whitespace (empty after substitution).
    private val EMPTY_XML_BLOCK = Regex("""<\w+>\n+</\w+>\n?""")

    // Collapses three or more consecutive newlines into two (one blank line).
    private val EXCESS_NEWLINES = Regex("""\n{3,}""")

    fun assemble(request: BundleRequest): BundleResult {
        val tree = TreeRenderer.render(request.items.mapNotNull { it.relativePath })
        val files = FileBlockRenderer.render(request.items)

        val raw =
            PLACEHOLDER.replace(request.options.template) { match ->
                when (match.groupValues[1]) {
                    "tree" -> tree
                    "files" -> files
                    "query" -> request.query
                    else -> match.value
                }
            }
        val text = EXCESS_NEWLINES.replace(EMPTY_XML_BLOCK.replace(raw, ""), "\n\n")
        return BundleResult(text)
    }
}
