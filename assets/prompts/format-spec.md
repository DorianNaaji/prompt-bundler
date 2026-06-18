# Prompt bundle format specification

This document specifies the deterministic text produced by the engine. The engine is the
reference implementation; any port (for example a future VS Code extension) must reproduce
the exact same output, which the shared golden files in `golden/` lock down.

## Overview

The engine fills a template with three placeholders. The default template lives in
`templates/default-template.md` and is embedded at build time:

```
You are an expert software developer. Analyze the technical context below and
answer the final request based exclusively on these elements.

### CONTEXT TREE
{tree}

### FILE CONTENTS
{files}

### USER REQUEST
{query}
```

- `{tree}` - the project tree of all attached paths.
- `{files}` - the content of each attached file, wrapped in a `<file>` block.
- `{query}` - the user request, verbatim (may be empty).

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

## Guards (not part of the output)

`ContentInspector` exposes pure helpers (`isBinary`, `isOversized`) used by the editor
integration to skip binary or oversized files before they reach the engine. They never
alter the assembled text.

## Versioning

This format is the contract validated by the golden files. Any intentional change must
update both this document and the affected golden files in the same change.
