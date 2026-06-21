package dev.promptbundler.engine

/**
 * A single piece of context attached to a bundle: the verbatim content of a file or of a
 * path-less snippet (such as a captured console output), together with the identity used to
 * render it in the assembled prompt.
 *
 * An item is either file-backed (`relativePath` set, `label` null) or label-backed
 * (`label` set, `relativePath` null), never both: file-backed items appear in the tree and
 * render as `<file>` blocks; label-backed items stay out of the tree and render as
 * `<context>` blocks identified by their label.
 *
 * @property relativePath path shown in the tree and in the file block header, or `null` for
 *   a label-backed snippet. When set, it is expected to be project-relative and to use `/`
 *   as separator, independent of the host operating system. Leading and trailing slashes
 *   are ignored by the renderers.
 * @property content the raw text content, embedded as-is (no escaping, no trimming).
 * @property lines optional 1-based, inclusive line range when the item is a selection
 *   snippet rather than a whole file. `null` means the full file: the file block then omits
 *   the `lines` attribute and renders exactly as before.
 * @property label human-readable identity for a path-less snippet (for example
 *   `Console output (Run: MyTest)`). `null` for file-backed items.
 */
data class ContextItem(
    val relativePath: String? = null,
    val content: String,
    val lines: IntRange? = null,
    val label: String? = null,
) {
    init {
        require((relativePath == null) != (label == null)) {
            "ContextItem must have exactly one of relativePath or label"
        }
    }
}

/**
 * Options controlling how a [BundleRequest] is assembled.
 *
 * @property template the prompt template, with `{tree}`, `{files}` and `{query}`
 *   placeholders. Defaults to the embedded English template shipped in
 *   `assets/prompts/`. Injectable to prepare template customization (M7).
 */
data class BundleOptions(
    val template: String = DefaultTemplate.text,
)

/**
 * A request to assemble a meta-prompt.
 *
 * @property query the user request, substituted into the `{query}` placeholder. May be
 *   blank.
 * @property items the attached context items. Order is irrelevant: the assembler sorts
 *   them deterministically by [ContextItem.relativePath] so the output is reproducible.
 * @property options assembly options, defaulting to the embedded template.
 */
data class BundleRequest(
    val query: String,
    val items: List<ContextItem>,
    val options: BundleOptions = BundleOptions(),
)

/**
 * The result of assembling a [BundleRequest].
 *
 * @property text the final meta-prompt, ready to be copied to the clipboard.
 */
data class BundleResult(
    val text: String,
)
