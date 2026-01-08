# AGENTS.md - AI Agent Instructions

## Project Overview

**dq** is a Kotlin monorepo for financial applications, owned by the `didrik-quant` GitHub organization.

- **Build System**: Bazel 8.5.0 with Bzlmod
- **Language**: Kotlin 2.2.x targeting JDK 21
- **Linting**: ktlint (via ktlint_test rules)

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

- Follow ktlint rules (enforced via ktlint_test targets)
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
2. Add `BUILD.bazel` with `kt_jvm_library` and `ktlint_test` targets
3. Add Kotlin source files

Example BUILD.bazel for a new module:
```python
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")
load("@rules_kotlin//kotlin:lint.bzl", "ktlint_test")

kt_jvm_library(
    name = "mymodule",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    visibility = ["//visibility:public"],
)

ktlint_test(
    name = "ktlint",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
)
```

## Project Structure

```
dq/
├── MODULE.bazel      # Bzlmod dependencies
├── BUILD.bazel       # Root build + Kotlin toolchain
├── .bazelrc          # Bazel configuration
├── bot/              # Main MM bot application
├── core/             # Core domain types (Event, Command, OrderBook)
├── strategy/         # Trading strategies (AgentXrpStrategy, SimpleMarketMaker)
├── execution/        # Order management
├── risk/             # Risk checking and kill switch
├── kraken-client/    # Kraken Futures API client
├── replay/           # Traffic recording for analysis
├── cli/              # CLI tools (dq fills, dq book)
├── harness/          # Epoch-based evolution harness
├── agents/           # Per-instrument evolution logs
├── example/          # Example module
└── tools/            # Build tooling
```

## Epoch-Based Strategy Evolution

The harness (`//harness:harness`) orchestrates strategy evolution through live trading epochs.

### Workflow

1. **Harness creates a git worktree** for isolation
2. **Agent receives a prompt** to improve the strategy
3. **Agent modifies** `strategy/src/main/kotlin/.../AgentXrpStrategy.kt`
4. **Agent runs** `bazel build //...` to verify changes compile
5. **Harness runs the bot** live until trade target is reached (or max duration)
6. **Results are logged** to `agents/<instrument>/evolution.md`
7. **Changes are merged** back to main (if successful)
8. **Loop repeats**

### Important Files

| File | Purpose |
|------|---------|
| `strategy/.../AgentXrpStrategy.kt` | Strategy code to evolve |
| `agents/PF_XRPUSD/evolution.md` | History of changes and results |

### CLI Tools

```bash
# View fills from last epoch
dq fills

# View order book at a specific timestamp
dq book --at <timestamp_ms>
```

### Configuration

Set in `~/.dq/harness.env`:

| Variable | Description | Default |
|----------|-------------|---------|
| `HARNESS_REPO_ROOT` | Path to dq repo | (required) |
| `HARNESS_INSTRUMENT` | Trading instrument | `PF_XRPUSD` |
| `HARNESS_EPOCH_TRADE_COUNT` | Number of trades per epoch | `50` |
| `HARNESS_EPOCH_MAX_DURATION_MS` | Safety timeout in ms | `7200000` (2 hours) |
| `HARNESS_STRATEGY_CLASS` | Strategy class name | `AgentXrpStrategy` |
| `OPENCODE_MODEL` | LLM model to use | `anthropic/claude-opus-4-5` |
| `KRAKEN_API_KEY` | Kraken API key | (required) |
| `KRAKEN_API_SECRET` | Kraken API secret | (required) |

### Running the Harness

```bash
# Terminal 1: Start opencode server
opencode serve --port 4096

# Terminal 2: Run harness
bazel run //harness:harness
```

### Traffic Recording

Events are recorded during live trading to `~/.dq/recordings` for analysis via CLI tools.

- **Retention**: 30 days rolling window
- **Format**: Chronicle Queue with ZSTD compression
