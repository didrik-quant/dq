# CLAUDE.md

Kotlin monorepo for financial trading applications. Bazel 8.5.0 with Bzlmod, Kotlin 2.2.x, JDK 21.

## Build Commands

```bash
bazel build //...              # Build everything
bazel test //...               # Run all tests
bazel test //... --test_tag_filters=ktlint  # Lint check
bazel run //module:ktlint_fix  # Auto-fix lint in module
```

## Code Style

- ktlint enforced (config in .editorconfig)
- 4-space indentation, 120 char max line length
- Explicit API mode for public APIs

## No Backward Compatibility

Delete obsolete code immediately. No deprecated APIs, no compatibility shims. Update all consumers in the same commit.

## Project Structure

- `bot/` - Main MM bot application
- `core/` - Domain types (Event, Command, OrderBook, Snapshots)
- `strategy/` - Trading strategies
- `execution/` - Order management
- `risk/` - Risk checking
- `kraken-client/` - Kraken Futures API client
- `replay/` - Traffic recording
- `cli/` - CLI tools (dq epoch)
- `harness/` - Epoch-based evolution harness

## Disruptor Pipeline

Handler chain (order matters):
```
ExecutionStateHandler → BookHandler → StrategyHandler → RiskHandler → OutputHandler → ExecutionUpdateHandler → EpochGuardHandler → MonitoringHandler → EventRecorder → CleanupHandler
```

**Handlers MUST NOT share mutable state.** All data flows through `MutableEvent` via immutable snapshots.

## Fail-Fast Principle

Throw `BotFatalException` on errors. No recovery, no graceful degradation. Exception handler cancels all orders and exits.

## No Coroutines or Spinlocks

No polling loops, spinlocks, or busy-waiting. All control flow through Disruptor pipeline. Exception: command-sending coroutine for WebSocket bridge.

## Git Workflow

Always create Pull Requests for code changes. Never merge directly to main. Use feature branches and worktrees for isolation.

## Superpowers Skills

Always use superpowers skills when:
- **Implementing features** - use brainstorming, writing-plans, test-driven-development
- **Debugging** - use systematic-debugging before proposing fixes
- **Brainstorming** - use brainstorming skill before any creative or design work

### Mandatory Workflow for Implementation

1. **Brainstorm first** - Refine ideas through questions, explore alternatives, validate design in sections
2. **Create worktree** - Use `superpowers:using-git-worktrees` BEFORE writing any code
3. **Write plan** - Use `superpowers:writing-plans` to create detailed implementation plan in `docs/plans/`
4. **Execute with subagents** - Use `superpowers:subagent-driven-development` for task-by-task execution with two-stage review
5. **Verify before completion** - Use `superpowers:verification-before-completion` before claiming work is done
6. **Finish branch** - Use `superpowers:finishing-a-development-branch` for merge/PR decision

### Critical Rules

- **NEVER implement directly on main** - Always use a worktree/feature branch
- **NEVER skip the worktree step** - Even for "small" changes
- **Plans go in `docs/plans/YYYY-MM-DD-<topic>.md`**
- **Verify tests pass BEFORE and AFTER changes**
