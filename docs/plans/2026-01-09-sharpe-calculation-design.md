# Sharpe Ratio Calculation for Harness Epochs

## Overview

Calculate per-epoch Sharpe ratio using a time-series approach (Carver-style) to evaluate strategy performance during evolution.

## Design Decisions

| Decision | Choice |
|----------|--------|
| Return calculation | Time-series, not per-trade |
| Sample interval | Every 1 second |
| Data source | ChronicleEventStore (post-hoc) |
| Return formula | `position × (mid_t - mid_{t-1}) / starting_equity` |
| Capital | Fixed from config, no compounding |
| Annualization | `× sqrt(31,536,000)` (seconds per year) |
| Minimum data | 300 seconds (~5 minutes) |
| Null handling | Log Sharpe as 0 |

## Algorithm

After bot completes an epoch:

1. Load all book updates and fills from ChronicleEventStore for the epoch time window
2. Generate second-by-second timestamps from start to end
3. For each second, reconstruct:
   - Position (cumulative from fills up to that point)
   - Mid price (last known from book updates)
4. Calculate returns: `return_t = position_{t-1} × (mid_t - mid_{t-1}) / starting_equity`
5. Compute Sharpe: `mean(returns) / stdev(returns) × sqrt(seconds_per_year)`

## Position Tracking

```kotlin
data class Snapshot(
    val timestamp: Instant,
    val position: BigDecimal,
    val mid: BigDecimal,
)

fun buildSnapshots(
    seconds: List<Instant>,
    fills: List<OrderFill>,
    bookUpdates: List<BookUpdate>,
): List<Snapshot> {
    var position = BigDecimal.ZERO
    var lastMid: BigDecimal? = null
    var fillIndex = 0
    var bookIndex = 0

    return seconds.map { ts ->
        val tsMillis = ts.toEpochMilli()

        // Advance fills up to this second
        while (fillIndex < fills.size && fills[fillIndex].timestamp <= tsMillis) {
            val fill = fills[fillIndex]
            position += if (fill.side == Side.BUY) fill.fillQty else -fill.fillQty
            fillIndex++
        }

        // Advance book updates to get latest mid
        while (bookIndex < bookUpdates.size && bookUpdates[bookIndex].timestamp <= tsMillis) {
            val book = bookUpdates[bookIndex]
            lastMid = (book.bids.first().price + book.asks.first().price) / BigDecimal(2)
            bookIndex++
        }

        Snapshot(ts, position, lastMid ?: BigDecimal.ZERO)
    }
}
```

Edge cases:
- No book data at epoch start: skip those seconds
- Gaps in book data: carry forward last known mid

## SharpeCalculator Class

```kotlin
class SharpeCalculator(private val dataDir: Path) {

    fun calculate(
        startTime: Instant,
        endTime: Instant,
        symbol: String,
        startingEquity: BigDecimal,
    ): BigDecimal? {
        val bookUpdates = loadBookUpdates(startTime, endTime, symbol)
        val fills = loadFills(startTime, endTime, symbol)

        val seconds = generateSequence(startTime) { it.plusSeconds(1) }
            .takeWhile { !it.isAfter(endTime) }
            .toList()

        val snapshots = buildSnapshots(seconds, fills, bookUpdates)

        val returns = mutableListOf<BigDecimal>()
        for (i in 1 until snapshots.size) {
            val prevMid = snapshots[i-1].mid
            val currMid = snapshots[i].mid
            val position = snapshots[i-1].position

            if (prevMid == BigDecimal.ZERO) continue  // skip until we have book data

            val pnl = position * (currMid - prevMid)
            val ret = pnl / startingEquity
            returns.add(ret)
        }

        if (returns.size < 300) return null  // ~5 minutes minimum

        val mean = returns.average()
        val stdev = returns.standardDeviation()
        if (stdev == BigDecimal.ZERO) return null

        val secondsPerYear = BigDecimal(31_536_000)
        return (mean / stdev) * sqrt(secondsPerYear)
    }
}
```

## File Changes

| Action | File |
|--------|------|
| Create | `harness/src/main/kotlin/com/didrikquant/harness/SharpeCalculator.kt` |
| Modify | `harness/src/main/kotlin/com/didrikquant/harness/HarnessConfig.kt` |
| Modify | `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt` |

## Config Addition

```kotlin
data class HarnessConfig(
    // ... existing fields ...
    val startingEquity: BigDecimal,  // env: STARTING_EQUITY
)
```

## Integration

In `Harness.runEpoch()`:

```kotlin
val startTime = Instant.now()
val botResult = runBot(worktreePath)
val endTime = Instant.now()

if (botResult.crashed) {
    // ... existing failure handling ...
    return
}

val sharpe = sharpeCalculator.calculate(
    startTime = startTime,
    endTime = endTime,
    symbol = config.instrument,
    startingEquity = config.startingEquity,
)

evolutionLog.append(epoch, startTime, endTime, diff, sharpe ?: BigDecimal.ZERO)
```
