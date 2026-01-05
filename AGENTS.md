# AGENTS.md - AI Agent Instructions

## Project Overview

**dq** is a Kotlin monorepo for financial applications, owned by the `didrik-quant` GitHub organization.

- **Build System**: Bazel 8.5.0 with Bzlmod
- **Language**: Kotlin 2.2.x targeting JDK 21
- **Linting**: ktlint (integrated via rules_kotlin aspect)

## Build Commands

```bash
# Build everything
bazel build //...

# Build a specific target
bazel build //example:example

# Run all tests
bazel test //...

# Clean build artifacts
bazel clean

# Clean everything including external dependencies
bazel clean --expunge
```

## Code Style

- Follow ktlint rules (enforced via Bazel aspect)
- 4-space indentation
- Max line length: 120 characters
- Use explicit API mode for public APIs

## Adding Dependencies

Maven dependencies are managed in `MODULE.bazel`:

```python
maven.install(
    artifacts = [
        "group:artifact:version",
    ],
    ...
)
```

Then reference in BUILD.bazel:
```python
deps = ["@maven//:group_artifact"]
```

## Creating New Modules

1. Create directory: `mkdir -p newmodule/src/main/kotlin/com/didrikquant/newmodule`
2. Add `BUILD.bazel` with `kt_jvm_library` target
3. Add Kotlin source files

## Project Structure

```
dq/
├── MODULE.bazel      # Bzlmod dependencies
├── BUILD.bazel       # Root build + Kotlin toolchain
├── .bazelrc          # Bazel configuration
├── example/          # Example module
└── tools/            # Build tooling
```
