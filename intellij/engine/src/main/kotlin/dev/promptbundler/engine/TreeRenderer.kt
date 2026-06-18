package dev.promptbundler.engine

/**
 * Renders the project tree shown in the `{tree}` placeholder from a set of relative paths.
 *
 * The output uses Unix `tree` connectors (`├──`, `└──`, `│`). Top-level entries are
 * printed flush-left without a connector; their descendants are indented with connectors.
 * Ordering is deterministic and independent of input order: at every level, directories
 * come before files, and entries are sorted lexicographically by name. This guarantees a
 * reproducible output that golden files can lock down.
 */
object TreeRenderer {
    private const val TEE = "├── "
    private const val ELBOW = "└── "
    private const val PIPE = "│   "
    private const val SPACE = "    "

    private class Node(
        val name: String,
    ) {
        val children = LinkedHashMap<String, Node>()

        val isDirectory: Boolean get() = children.isNotEmpty()
    }

    /** Builds the tree text from the given relative paths. Returns "" when empty. */
    fun render(relativePaths: Collection<String>): String {
        val roots = LinkedHashMap<String, Node>()
        for (path in relativePaths) {
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            var level = roots
            var node: Node? = null
            for (segment in segments) {
                node = level.getOrPut(segment) { Node(segment) }
                level = node.children
            }
        }

        val builder = StringBuilder()
        val sortedRoots = roots.values.sortedWith(NODE_ORDER)
        for (root in sortedRoots) {
            builder.append(root.name).append('\n')
            appendChildren(root, "", builder)
        }
        return builder.toString().trimEnd('\n')
    }

    private fun appendChildren(
        node: Node,
        prefix: String,
        builder: StringBuilder,
    ) {
        val children = node.children.values.sortedWith(NODE_ORDER)
        children.forEachIndexed { index, child ->
            val isLast = index == children.lastIndex
            builder
                .append(prefix)
                .append(if (isLast) ELBOW else TEE)
                .append(child.name)
                .append('\n')
            appendChildren(child, prefix + (if (isLast) SPACE else PIPE), builder)
        }
    }

    private val NODE_ORDER =
        compareByDescending<Node> { it.isDirectory }.thenBy { it.name }
}
