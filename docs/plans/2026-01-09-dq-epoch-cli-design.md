# dq epoch CLI Design

CLI command for strategy-developing agents to analyze epoch performance.

## Overview

Replace existing `dq fills` and `dq book` commands with a single `dq epoch` command that provides comprehensive metrics for agent-driven strategy evolution.

## Command Interface

```bash
dq epoch --from <timestamp> --to <timestamp>
```

**Arguments:**
- `--from` - Epoch start timestamp in milliseconds (required)
- `--to` - Epoch end timestamp in milliseconds (required)

**Comparison mode:**
```bash
dq epoch --from <ts> --to <ts> --compare-from <ts> --compare-to <ts>
```

When comparison timestamps are provided, output includes a side-by-side comparison section showing deltas.

## Output Format

Markdown document with four sections:

```markdown
## Epoch Summary

**Period:** 2026-01-09 10:00:00 - 11:00:00 UTC
**Duration:** 1h 0m
**Fills:** 47

## P&L

| Metric | Value |
|--------|-------|
| Realized P&L | $12.34 |
| Fees Paid | $2.10 |
| Net P&L | $10.24 |
| Avg Fill Price vs Mid | -0.3 bps |

## Execution

| Metric | Value |
|--------|-------|
| Total Fills | 47 |
| Buy Fills | 24 |
| Sell Fills | 23 |
| Avg Time to Fill | 340ms |
| Fill Rate | 78% |

## Risk

| Metric | Value |
|--------|-------|
| Max Long Position | 45 XRP |
| Max Short Position | -30 XRP |
| Avg Inventory | 8.2 XRP |
| Max Drawdown | $3.20 |
```

## Comparison Output

When `--compare-from` and `--compare-to` are provided:

```markdown
## Comparison (Previous -> Current)

| Metric | Previous | Current | Delta |
|--------|----------|---------|-------|
| Net P&L | $6.50 | $10.24 | +$3.74 |
| Sharpe | 0.8 | 1.2 | +0.4 |
| Total Fills | 52 | 47 | -5 |
| Fill Rate | 72% | 78% | +6% |
| Avg Inventory | 12.1 XRP | 8.2 XRP | -3.9 |
| Max Drawdown | $5.10 | $3.20 | -$1.90 |

### Interpretation

- P&L improved despite fewer fills
- Tighter inventory management (lower avg position)
- Reduced drawdown suggests better risk control
```

## Implementation

**File changes:**
- Remove `cli/src/main/kotlin/com/didrikquant/cli/FillsCommand.kt`
- Remove `cli/src/main/kotlin/com/didrikquant/cli/BookCommand.kt`
- Add `cli/src/main/kotlin/com/didrikquant/cli/EpochCommand.kt`
- Update `cli/src/main/kotlin/com/didrikquant/cli/Main.kt`

**Data sources from Chronicle events:**
- `Event.OrderFill` - For P&L and execution metrics
- `Event.OrderPlaced` / `Event.OrderCancelled` - For fill rate calculation
- `Event.BookUpdate` - For "fill price vs mid" calculation

**Computation approach:**
1. Seek to start timestamp in event store
2. Stream events until end timestamp
3. Accumulate metrics in mutable state
4. For position/inventory: replay order fills to compute running position
5. For Sharpe: derive from fill prices or book updates

## Error Handling

**No data in range:**
```markdown
## Epoch Summary

**Period:** 2026-01-09 10:00:00 - 11:00:00 UTC
**Duration:** 1h 0m
**Fills:** 0

No fills recorded in this period.
```

**Missing event files:**
```
Error: No recorded data for 2026-01-09. Available dates: 2026-01-07, 2026-01-08
```

**Partial data:**
```markdown
**Note:** Data missing for 2026-01-08. Results may be incomplete.
```

## Integration with Evolution Workflow

**Evolution.md format** (harness provides timestamps):

```markdown
## Epoch 1 - Tighter Spread

### Changes
- spreadBps: 10 -> 8
- skewFactor: 0.0001 -> 0.00015

### Timestamps
- Start: 1736380800000
- End: 1736384400000

### Results
- Sharpe: 1.2
```

**Agent workflow:**
1. Read evolution.md, extract timestamps for current and previous epoch
2. Run `dq epoch` with timestamps
3. Read markdown output, reason about what worked
4. Modify strategy code
5. Commit and signal ready for next epoch

**Tools Available section** in evolution.md:
```markdown
## Tools Available

- `dq epoch --from <ts> --to <ts>` - View metrics for an epoch
- `dq epoch --from <ts> --to <ts> --compare-from <ts> --compare-to <ts>` - Compare two epochs
```
