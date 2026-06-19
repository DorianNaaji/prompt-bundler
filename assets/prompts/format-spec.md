# Prompt bundle format specification

This document specifies the deterministic text produced by the engine. The engine is the
reference implementation; any port (for example a future VS Code extension) must reproduce
the exact same output, which the shared golden files in `golden/` lock down.

## Overview

The engine fills a template with three placeholders. The default template lives in
`templates/default-template.md` and is embedded at build time:

```
You are an expert software developer. Your task is to answer the user's request using the
technical context below together with your own expertise.

Guidelines:
- Ground your answer in the provided project context. If the context is empty or
  insufficient, rely on your broader knowledge.
- Do not invent files, functions, or APIs that are absent from the context, unless the
  request explicitly asks you to create new ones.
- Be concise. Explain your reasoning before writing code, and format every code change in a
  proper markdown code block.

<context_tree>
{tree}
</context_tree>

<file_contents>
{files}
</file_contents>

### USER REQUEST
{query}
```

- `{tree}` - the project tree of all attached paths, wrapped in a `<context_tree>` block.
- `{files}` - the content of each attached file, each wrapped in a `<file>` block, the whole
  set wrapped in a `<file_contents>` block.
- `{query}` - the user request, verbatim (may be empty).

The injected context is isolated inside XML tags (`<context_tree>`, `<file_contents>`,
`<file>`) so the model never confuses raw project text with the instructions above it. The
leading guidelines keep the answer grounded in the context, forbid inventing absent files,
and ask for concise, markdown-formatted output.

Placeholders are substituted in a single pass, so substituted content that itself contains
`{tree}`, `{files}` or `{query}` is never re-expanded.

## Determinism and ordering

Output never depends on the order in which items are provided. At every level:

1. directories come before files;
2. entries are sorted lexicographically by name.

The same ordering applies to the tree and to the file blocks, so a given set of items
always yields a byte-identical result.

## Tree section (`{tree}`)

The tree uses Unix `tree` connectors. Top-level entries are printed flush-left with no
connector; their descendants are indented with connectors:

- `+-- ` (├── ) for a non-last child;
- `\-- ` (└── ) for the last child;
- `|   ` (│ + spaces) to continue an ancestor branch;
- four spaces once an ancestor branch has ended.

Path segments are split on `/`; empty segments and leading/trailing slashes are ignored.
Path separators are always `/`, independent of the host operating system. The tree is empty
when there are no items.

## Files section (`{files}`)

Each item is rendered as:

```
<file path="relative/path">
raw content
</file>
```

- `path` is the project-relative path, verbatim (special characters preserved, not escaped).
- Content is embedded verbatim; a single trailing newline is ensured before `</file>`.
- Blocks are separated by one blank line.

A selection snippet (a range pulled from a file rather than the whole file) carries an extra
`lines` attribute with a 1-based, inclusive range:

```
<file path="src/main/kotlin/App.kt" lines="10-12">
raw content
</file>
```

When two items share a path, blocks are ordered by their starting line; an item without a
`lines` attribute sorts before any snippet of the same path.

## Guards (not part of the output)

`ContentInspector` exposes pure helpers (`isBinary`, `isOversized`) used by the editor
integration to skip binary or oversized files before they reach the engine. They never
alter the assembled text.

## Versioning

This format is the contract validated by the golden files. Any intentional change must
update both this document and the affected golden files in the same change.
