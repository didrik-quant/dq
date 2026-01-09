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

## Epoch Harness

The harness runs an automated evolution loop: spawn Claude agent → build strategy → run bot → measure Sharpe → repeat.

### Configuration

Create `~/.dq/harness.env`:

```bash
HARNESS_REPO_ROOT=/path/to/dq
KRAKEN_API_KEY=your_key
KRAKEN_API_SECRET=your_secret
```

### Running

```bash
bazel run //harness:harness
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `HARNESS_REPO_ROOT` | (required) | Path to the dq repository |
| `HARNESS_INSTRUMENT` | `PF_XRPUSD` | Trading instrument |
| `HARNESS_STRATEGY_CLASS` | `AgentXrpStrategy` | Strategy class name |
| `HARNESS_EPOCH_TRADE_COUNT` | `50` | Trades before epoch ends |
| `HARNESS_EPOCH_MAX_DURATION_MS` | `7200000` | Max epoch duration (2 hours) |
| `HARNESS_GRACE_PERIOD_MS` | `60000` | Grace period for bot shutdown |
| `HARNESS_SKIP_AGENT` | `false` | Skip Claude agent (for debugging) |
| `HARNESS_DRY_RUN` | `false` | Run bot without placing orders |
| `KRAKEN_API_KEY` | - | Kraken API key |
| `KRAKEN_API_SECRET` | - | Kraken API secret |
| `DATA_DIR` | `~/.dq/data` | Data directory |
| `STARTING_EQUITY` | `10000` | Starting equity for Sharpe calculation |

### Debug Mode

For debugging without Claude or Kraken credentials:

```bash
HARNESS_SKIP_AGENT=true HARNESS_DRY_RUN=true bazel run //harness:harness
```

## Stack

- **Build**: Bazel 8.5.0 with Bzlmod
- **Language**: Kotlin 2.2.x
- **JVM**: JDK 21
- **Linting**: ktlint
