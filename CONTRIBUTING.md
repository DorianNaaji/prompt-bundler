# Contributing to Prompt Bundler

Thanks for your interest in improving Prompt Bundler. This guide covers the basics.

## Prerequisites

- JDK 21 (the Gradle build pins the toolchain to 21 and will provision it if needed).
- Git.

No global Gradle install is required; use the bundled wrapper (`./gradlew`).

## Project layout

- `intellij/engine/` - pure-Kotlin, deterministic logic. Must stay free of IDE
  dependencies and be covered by unit tests (golden files live in `assets/prompts/`).
- `intellij/plugin/` - IntelliJ-platform wiring (actions, tool windows, persistence).
- `assets/prompts/` - shared, code-free prompt assets (templates, golden files, format spec).

## Local checks

Before opening a pull request, make sure the full build is green:

```bash
./gradlew build verifyPlugin
```

This runs compilation, unit tests, ktlint, and the IntelliJ Plugin Verifier. To try
the plugin in a sandbox IDE:

```bash
./gradlew runIde
```

## Conventions

- Everything in the codebase and public documentation is written in English.
- Code is formatted and linted with ktlint (`./gradlew ktlintFormat` to auto-fix).
- Do not leave the build or tests broken in any commit.
- Keep commits focused and write clear, imperative commit messages.

## Pull requests

1. Fork and branch from `main`.
2. Make your change with accompanying tests.
3. Ensure `./gradlew build verifyPlugin` passes.
4. Open a pull request describing the change and its motivation.
