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
├── strategy/         # Trading strategies (SimpleMarketMaker)
├── execution/        # Order management
├── risk/             # Risk checking and kill switch
├── kraken-client/    # Kraken Futures API client
├── replay/           # Traffic recording and replay system
├── backtest/         # Regression testing framework
├── example/          # Example module
└── tools/            # Build tooling
```

## Regression Testing

**IMPORTANT**: Before deploying changes to the strategy or pipeline components, always run the regression tests.

```bash
# Run regression tests against recorded market data
bazel test //backtest:regression_test --test_tag_filters=regression
```

### When to Run Regression Tests

Run regression tests before modifying:
- Any file in `strategy/`
- Any handler in `bot/src/main/kotlin/.../handlers/`
- `core/` event types or order book logic
- `execution/` order management
- `risk/` configuration or checks

### Test Data

Tests replay recorded market data from the live bot. Data is stored in Chronicle Queue format at `~/.dq/recordings`.

- **Retention**: 30 days rolling window
- **Recording**: Always-on during live bot operation
- **Bootstrap**: Tests will be skipped if no data is available yet (data accumulates over time)

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REPLAY_DATA_DIR` | Directory for recorded events | `~/.dq/recordings` |
| `REPLAY_RETENTION_DAYS` | Days to retain recordings | `30` |
| `BACKTEST_START_DAYS` | Days ago to start backtest | `7` |
| `BACKTEST_END_DAYS` | Days ago to end backtest | `1` |
| `BACKTEST_SYMBOL` | Symbol to backtest | `PF_XRPUSD` |

### CI Integration

The regression tests are tagged and can run separately:

```bash
# Run all tests EXCEPT regression
bazel test //... --test_tag_filters=-regression

# Run ONLY regression tests
bazel test //backtest:regression_test --test_tag_filters=regression
```

### Traffic Replay Architecture

The replay system has three main components:

1. **Recording** (`replay/recorder/`): Always-on event capture during live trading
2. **Replay** (`replay/player/`): Fast playback of recorded events
3. **Simulation** (`replay/simulator/`): Conservative fill model for testing

The simulation uses a simple trade-through model (fills when price crosses your level) which is intentionally conservative - real performance should be better.
