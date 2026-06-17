# assets/prompts/

Shared, code-free assets for the prompt format. The engine reads them; it does not
live here. Keeping them shared (rather than inside a single module's test resources)
lets every editor integration build and validate against the exact same files.

- `templates/` - prompt template seeds, starting with `default-template.md`.
- `format-spec.md` - placeholder for the prompt bundle format specification.
- `golden/` - golden files: reference outputs used to validate the engine.

## What are golden files?

The engine turns a fixed input into deterministic text (an assembled prompt bundle).
A golden test runs the engine on a known input and compares its output, byte for byte,
against a checked-in reference file in `golden/`. A mismatch means either a regression
to fix, or an intentional change, in which case the golden file is updated on purpose.

Because the golden files are shared, the IntelliJ engine and the planned VS Code port
can be verified against the same expected output, guaranteeing identical results across
implementations.
