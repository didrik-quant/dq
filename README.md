# dq

Kotlin monorepo for financial applications.

## Prerequisites

- [Bazelisk](https://github.com/bazelbuild/bazelisk) (recommended) or Bazel 8.5.0+
- JDK 21+

## Quick Start

```bash
# Build all targets
bazel build //...

# Run tests
bazel test //...
```

## Stack

- **Build**: Bazel 8.5.0 with Bzlmod
- **Language**: Kotlin 2.2.x
- **JVM**: JDK 21
- **Linting**: ktlint
