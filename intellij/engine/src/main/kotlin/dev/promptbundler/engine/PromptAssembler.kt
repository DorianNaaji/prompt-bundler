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

    fun assemble(request: BundleRequest): BundleResult {
        val tree = TreeRenderer.render(request.items.map { it.relativePath })
        val files = FileBlockRenderer.render(request.items)

        val text =
            PLACEHOLDER.replace(request.options.template) { match ->
                when (match.groupValues[1]) {
                    "tree" -> tree
                    "files" -> files
                    "query" -> request.query
                    else -> match.value
                }
            }
        return BundleResult(text)
    }
}
