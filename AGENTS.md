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

## No Backward Compatibility

This project does NOT maintain backward compatibility. When refactoring:

- Delete obsolete code, fields, and methods immediately
- Do not keep deprecated APIs "for compatibility"
- Do not add compatibility shims or adapters
- Update all consumers in the same commit

Clean, simple code is prioritized over migration paths.

## Linting (ktlint)

ktlint is integrated via Bazel using `rules_kotlin`. Configuration is in `.editorconfig`.

```bash
# Check all modules for lint errors
bazel test //... --test_tag_filters=ktlint

# Check a specific module
bazel test //core:ktlint

# Auto-fix lint errors in a module
bazel run //core:ktlint_fix

# Auto-fix all modules (run each)
bazel run //bot:ktlint_fix
bazel run //core:ktlint_fix
bazel run //cli:ktlint_fix
bazel run //execution:ktlint_fix
bazel run //harness:ktlint_fix
bazel run //kraken-client:ktlint_fix
bazel run //replay:ktlint_fix
bazel run //risk:ktlint_fix
bazel run //strategy:ktlint_fix
bazel run //example:ktlint_fix
```

Each module has two ktlint targets:
- `//module:ktlint` - Test target (fails build on lint errors)
- `//module:ktlint_fix` - Run target (auto-fixes what it can)

**Note**: Some errors (like `max-line-length`) cannot be auto-fixed and must be corrected manually.

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
load("@rules_kotlin//kotlin:lint.bzl", "ktlint_fix", "ktlint_test")

kt_jvm_library(
    name = "mymodule",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    visibility = ["//visibility:public"],
)

ktlint_test(
    name = "ktlint",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    config = "//:ktlint_config",
    tags = ["ktlint"],
)

ktlint_fix(
    name = "ktlint_fix",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    config = "//:ktlint_config",
)
```

## Project Structure

```
dq/
├── MODULE.bazel      # Bzlmod dependencies
├── BUILD.bazel       # Root build + Kotlin toolchain
├── .bazelrc          # Bazel configuration
├── bot/              # Main MM bot application
├── core/             # Core domain types (Event, Command, OrderBook, Snapshots)
├── strategy/         # Trading strategies (AgentXrpStrategy, SimpleMarketMaker)
├── execution/        # Order management
├── risk/             # Risk checking
├── kraken-client/    # Kraken Futures API client
├── replay/           # Traffic recording for analysis
├── cli/              # CLI tools (dq fills, dq book)
├── harness/          # Epoch-based evolution harness
├── agents/           # Per-instrument evolution logs
├── example/          # Example module
└── tools/            # Build tooling
```

## Disruptor Pipeline Architecture

The bot uses LMAX Disruptor for high-performance event processing. Events flow through a chain of handlers.

### Handler Chain

```
ExecutionStateHandler → BookHandler → StrategyHandler → RiskHandler → OutputHandler → ExecutionUpdateHandler → EpochGuardHandler → MonitoringHandler → EventRecorder → CleanupHandler
```

### Handler Independence (CRITICAL)

**Handlers MUST NOT share mutable state.** All data flows through `MutableEvent` via immutable snapshots.

#### Snapshots (immutable, created by state owners)
- `OrderBookSnapshot` - created by BookHandler after updating OrderBook
- `ExecutionSnapshot` - created by ExecutionStateHandler from OrderManager

#### Data Flow Through MutableEvent

| Handler | Reads From Event | Writes To Event |
|---------|------------------|-----------------|
| ExecutionStateHandler | order events | `executionSnapshot` |
| BookHandler | - | `orderBookSnapshot` |
| StrategyHandler | `orderBookSnapshot`, `executionSnapshot` | `actions` |
| RiskHandler | `executionSnapshot`, `actions` | `commands`, `newPendingOrders` |
| OutputHandler | `commands` | (sends to CommandSender) |
| ExecutionUpdateHandler | `newPendingOrders` | (registers pending orders) |
| EpochGuardHandler | fill events | (throws on epoch complete) |
| MonitoringHandler | `orderBookSnapshot`, `executionSnapshot` | (logs status) |
| CleanupHandler | - | (clears all fields) |

### MutableEvent Structure

```kotlin
public class MutableEvent {
    // Input event
    var event: Event? = null
    
    // Snapshots (immutable, set by state-owning handlers)
    var orderBookSnapshot: OrderBookSnapshot? = null
    var executionSnapshot: ExecutionSnapshot? = null
    
    // Strategy -> Risk
    var actions: List<StrategyAction> = emptyList()
    
    // Risk -> Output
    var commands: List<Command> = emptyList()
    
    // Risk -> ExecutionUpdate
    var newPendingOrders: List<PendingOrderIntent> = emptyList()
    
    fun clear() { /* resets all fields */ }
}
```

### Adding New Handlers

1. Create class implementing `EventHandler<MutableEvent>`
2. Read from snapshot fields set by upstream handlers
3. Write to event fields for downstream handlers
4. Add to handler list in `Pipeline.kt` (before CleanupHandler)
5. **NEVER inject or reference other handlers**
6. **NEVER share mutable state between handlers**

## Fail-Fast Principle

The bot runs inside a harness that manages trading epochs. When something goes wrong:

1. **Throw `BotFatalException`** - don't try to recover
2. **Exception handler cancels all orders** - via `CommandSender.cancelAllAndShutdown()`
3. **Process exits** - `System.exit(1)`
4. **Harness logs results** - epoch ends

### Fatal Conditions

- Max loss exceeded (PnL check in ExecutionStateHandler)
- Strategy exception (wrapped in BotFatalException)
- Order rejected by exchange (checked in ExecutionStateHandler)
- Epoch trade target reached (EpochGuardHandler)
- Epoch max duration reached (EpochGuardHandler)
- Any unexpected error

**NO error recovery. NO graceful degradation. Throw and die.**

## No Coroutines or Spinlocks

The bot MUST NOT use:
- Coroutine polling loops (`while (condition) { delay(...) }`)
- Spinlock patterns
- Busy-waiting of any kind

All control flow happens through the Disruptor pipeline:
- Market data → handlers process → actions generated
- Epoch limits → EpochGuardHandler throws → process exits
- Errors → exception handler → process exits

If you need periodic behavior, use a handler that checks conditions on each event.

**Exception**: The command-sending coroutine is acceptable because WebSocket `sendCommand()` is a suspend function that cannot be called from the Disruptor thread. This is a bridge between the non-coroutine Disruptor world and the coroutine WebSocket world.

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
