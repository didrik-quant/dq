# dq epoch CLI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace `dq fills` and `dq book` with a single `dq epoch` command that outputs comprehensive performance metrics in markdown format.

**Architecture:** Stream Chronicle events within a time range, accumulate metrics (P&L, execution, risk) in stateful accumulators, render to markdown. Comparison mode runs two accumulations and diffs the results.

**Tech Stack:** Kotlin, Bazel, Chronicle event store, BigDecimal for precision

---

## Task 1: Create EpochMetrics Data Class

**Files:**
- Create: `cli/src/main/kotlin/com/didrikquant/cli/EpochMetrics.kt`

**Step 1: Create the metrics data class**

```kotlin
package com.didrikquant.cli

import java.math.BigDecimal

public data class EpochMetrics(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val totalFills: Int,
    val buyFills: Int,
    val sellFills: Int,
    val realizedPnl: BigDecimal,
    val feesPaid: BigDecimal,
    val netPnl: BigDecimal,
    val avgFillPriceVsMidBps: BigDecimal?,
    val maxLongPosition: BigDecimal,
    val maxShortPosition: BigDecimal,
    val avgInventory: BigDecimal,
    val maxDrawdown: BigDecimal,
    val sharpe: BigDecimal?,
)
```

**Step 2: Run ktlint to verify formatting**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel test //cli:ktlint`
Expected: PASS

**Step 3: Commit**

```bash
git add cli/src/main/kotlin/com/didrikquant/cli/EpochMetrics.kt
git commit -m "feat(cli): add EpochMetrics data class"
```

---

## Task 2: Create MetricsAccumulator

**Files:**
- Create: `cli/src/main/kotlin/com/didrikquant/cli/MetricsAccumulator.kt`

**Step 1: Create accumulator that processes events**

```kotlin
package com.didrikquant.cli

import com.didrikquant.core.Event
import com.didrikquant.core.OrderBook
import com.didrikquant.core.ZERO
import com.didrikquant.core.safeDivide
import com.didrikquant.orderstate.Side
import java.math.BigDecimal
import kotlin.math.sqrt

private val FEE_RATE = BigDecimal("0.0002") // 2 bps taker fee

public class MetricsAccumulator(
    private val startTimestamp: Long,
    private val endTimestamp: Long,
) {
    private var orderBook: OrderBook? = null
    private var sequence: Long = 0

    private var totalFills = 0
    private var buyFills = 0
    private var sellFills = 0

    private var realizedPnl = ZERO
    private var feesPaid = ZERO
    private var position = ZERO
    private var costBasis = ZERO

    private var maxLongPosition = ZERO
    private var maxShortPosition = ZERO
    private var inventorySum = ZERO
    private var inventoryCount = 0L

    private var peakPnl = ZERO
    private var maxDrawdown = ZERO

    private var fillPriceVsMidSum = ZERO
    private var fillPriceVsMidCount = 0

    private val returns = mutableListOf<BigDecimal>()
    private var lastMidPrice: BigDecimal? = null

    public fun process(event: Event) {
        when (event) {
            is Event.BookSnapshot -> {
                if (orderBook == null || orderBook?.symbol != event.symbol) {
                    orderBook = OrderBook(event.symbol)
                }
                orderBook?.applySnapshot(event.bids, event.asks, sequence++)
                trackReturn()
            }
            is Event.BookUpdate -> {
                orderBook?.applyUpdate(event.bids, event.asks, sequence++)
                trackReturn()
            }
            is Event.OrderFill -> processFill(event)
            else -> {}
        }
    }

    private fun trackReturn() {
        val mid = orderBook?.midPrice ?: return
        val last = lastMidPrice
        if (last != null && last != ZERO) {
            val ret = (mid - last).safeDivide(last)
            returns.add(ret)
        }
        lastMidPrice = mid
    }

    private fun processFill(fill: Event.OrderFill) {
        totalFills++
        val fillValue = fill.fillPrice * fill.fillQty
        val fee = fillValue * FEE_RATE
        feesPaid += fee

        val midAtFill = orderBook?.midPrice
        if (midAtFill != null && midAtFill != ZERO) {
            val slippageBps = (fill.fillPrice - midAtFill).safeDivide(midAtFill) * BigDecimal("10000")
            fillPriceVsMidSum += if (fill.side == Side.BUY) slippageBps else -slippageBps
            fillPriceVsMidCount++
        }

        when (fill.side) {
            Side.BUY -> {
                buyFills++
                position += fill.fillQty
                costBasis += fillValue
            }
            Side.SELL -> {
                sellFills++
                val avgCost = if (position != ZERO) costBasis.safeDivide(position) else ZERO
                val pnl = (fill.fillPrice - avgCost) * fill.fillQty
                realizedPnl += pnl
                position -= fill.fillQty
                costBasis -= avgCost * fill.fillQty
            }
        }

        // Track position extremes
        if (position > maxLongPosition) maxLongPosition = position
        if (position < maxShortPosition) maxShortPosition = position
        inventorySum += position.abs()
        inventoryCount++

        // Track drawdown
        val currentPnl = realizedPnl - feesPaid
        if (currentPnl > peakPnl) peakPnl = currentPnl
        val drawdown = peakPnl - currentPnl
        if (drawdown > maxDrawdown) maxDrawdown = drawdown
    }

    public fun build(): EpochMetrics {
        val avgFillVsMid = if (fillPriceVsMidCount > 0) {
            fillPriceVsMidSum.safeDivide(BigDecimal(fillPriceVsMidCount))
        } else null

        val avgInventory = if (inventoryCount > 0) {
            inventorySum.safeDivide(BigDecimal(inventoryCount))
        } else ZERO

        val sharpe = calculateSharpe()

        return EpochMetrics(
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            totalFills = totalFills,
            buyFills = buyFills,
            sellFills = sellFills,
            realizedPnl = realizedPnl,
            feesPaid = feesPaid,
            netPnl = realizedPnl - feesPaid,
            avgFillPriceVsMidBps = avgFillVsMid,
            maxLongPosition = maxLongPosition,
            maxShortPosition = maxShortPosition,
            avgInventory = avgInventory,
            maxDrawdown = maxDrawdown,
            sharpe = sharpe,
        )
    }

    private fun calculateSharpe(): BigDecimal? {
        if (returns.size < 2) return null
        val mean = returns.reduce { a, b -> a + b }.safeDivide(BigDecimal(returns.size))
        val variance = returns.map { (it - mean) * (it - mean) }
            .reduce { a, b -> a + b }
            .safeDivide(BigDecimal(returns.size))
        val stdDev = BigDecimal(sqrt(variance.toDouble()))
        if (stdDev == ZERO) return null
        return mean.safeDivide(stdDev)
    }
}
```

**Step 2: Run ktlint to verify formatting**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel test //cli:ktlint`
Expected: PASS

**Step 3: Commit**

```bash
git add cli/src/main/kotlin/com/didrikquant/cli/MetricsAccumulator.kt
git commit -m "feat(cli): add MetricsAccumulator for epoch analysis"
```

---

## Task 3: Create MarkdownRenderer

**Files:**
- Create: `cli/src/main/kotlin/com/didrikquant/cli/MarkdownRenderer.kt`

**Step 1: Create renderer that formats metrics as markdown**

```kotlin
package com.didrikquant.cli

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

public object MarkdownRenderer {

    public fun render(metrics: EpochMetrics): String = buildString {
        appendLine("## Epoch Summary")
        appendLine()
        val start = Instant.ofEpochMilli(metrics.startTimestamp)
        val end = Instant.ofEpochMilli(metrics.endTimestamp)
        val duration = Duration.between(start, end)
        appendLine("**Period:** ${DATETIME_FORMAT.format(start)} - ${DATETIME_FORMAT.format(end)} UTC")
        appendLine("**Duration:** ${formatDuration(duration)}")
        appendLine("**Fills:** ${metrics.totalFills}")
        appendLine()

        if (metrics.totalFills == 0) {
            appendLine("No fills recorded in this period.")
            return@buildString
        }

        appendLine("## P&L")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|--------|-------|")
        appendLine("| Realized P&L | \$${metrics.realizedPnl.setScale(2)} |")
        appendLine("| Fees Paid | \$${metrics.feesPaid.setScale(2)} |")
        appendLine("| Net P&L | \$${metrics.netPnl.setScale(2)} |")
        val avgVsMid = metrics.avgFillPriceVsMidBps?.setScale(1)?.let { "$it bps" } ?: "N/A"
        appendLine("| Avg Fill Price vs Mid | $avgVsMid |")
        appendLine()

        appendLine("## Execution")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|--------|-------|")
        appendLine("| Total Fills | ${metrics.totalFills} |")
        appendLine("| Buy Fills | ${metrics.buyFills} |")
        appendLine("| Sell Fills | ${metrics.sellFills} |")
        appendLine()

        appendLine("## Risk")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|--------|-------|")
        appendLine("| Max Long Position | ${metrics.maxLongPosition.setScale(2)} |")
        appendLine("| Max Short Position | ${metrics.maxShortPosition.setScale(2)} |")
        appendLine("| Avg Inventory | ${metrics.avgInventory.setScale(2)} |")
        appendLine("| Max Drawdown | \$${metrics.maxDrawdown.setScale(2)} |")
        val sharpeStr = metrics.sharpe?.setScale(2)?.toString() ?: "N/A"
        appendLine("| Sharpe | $sharpeStr |")
    }

    public fun renderComparison(previous: EpochMetrics, current: EpochMetrics): String = buildString {
        appendLine("## Comparison (Previous -> Current)")
        appendLine()
        appendLine("| Metric | Previous | Current | Delta |")
        appendLine("|--------|----------|---------|-------|")

        fun row(name: String, prev: String, curr: String, delta: String) {
            appendLine("| $name | $prev | $curr | $delta |")
        }

        row(
            "Net P&L",
            "\$${previous.netPnl.setScale(2)}",
            "\$${current.netPnl.setScale(2)}",
            formatDelta(current.netPnl - previous.netPnl, prefix = "\$"),
        )

        val prevSharpe = previous.sharpe?.setScale(2)?.toString() ?: "N/A"
        val currSharpe = current.sharpe?.setScale(2)?.toString() ?: "N/A"
        val sharpeDelta = if (previous.sharpe != null && current.sharpe != null) {
            formatDelta(current.sharpe - previous.sharpe)
        } else "N/A"
        row("Sharpe", prevSharpe, currSharpe, sharpeDelta)

        row(
            "Total Fills",
            previous.totalFills.toString(),
            current.totalFills.toString(),
            formatDelta(current.totalFills - previous.totalFills),
        )

        row(
            "Avg Inventory",
            previous.avgInventory.setScale(2).toString(),
            current.avgInventory.setScale(2).toString(),
            formatDelta(current.avgInventory - previous.avgInventory),
        )

        row(
            "Max Drawdown",
            "\$${previous.maxDrawdown.setScale(2)}",
            "\$${current.maxDrawdown.setScale(2)}",
            formatDelta(current.maxDrawdown - previous.maxDrawdown, prefix = "\$"),
        )

        appendLine()
        appendLine("### Interpretation")
        appendLine()
        generateInterpretation(previous, current).forEach { appendLine("- $it") }
    }

    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        return "${hours}h ${minutes}m"
    }

    private fun formatDelta(value: Number, prefix: String = ""): String {
        val num = when (value) {
            is Int -> value.toDouble()
            is java.math.BigDecimal -> value.toDouble()
            else -> value.toDouble()
        }
        val sign = if (num >= 0) "+" else ""
        return when (value) {
            is Int -> "$sign$value"
            is java.math.BigDecimal -> "$sign$prefix${value.setScale(2)}"
            else -> "$sign$prefix$value"
        }
    }

    private fun generateInterpretation(previous: EpochMetrics, current: EpochMetrics): List<String> {
        val insights = mutableListOf<String>()

        if (current.netPnl > previous.netPnl) {
            insights.add("P&L improved by \$${(current.netPnl - previous.netPnl).setScale(2)}")
        } else if (current.netPnl < previous.netPnl) {
            insights.add("P&L decreased by \$${(previous.netPnl - current.netPnl).setScale(2)}")
        }

        if (current.avgInventory < previous.avgInventory) {
            insights.add("Tighter inventory management (lower avg position)")
        } else if (current.avgInventory > previous.avgInventory) {
            insights.add("Higher average inventory exposure")
        }

        if (current.maxDrawdown < previous.maxDrawdown) {
            insights.add("Reduced drawdown suggests better risk control")
        } else if (current.maxDrawdown > previous.maxDrawdown) {
            insights.add("Increased drawdown - may need tighter risk limits")
        }

        if (insights.isEmpty()) {
            insights.add("Metrics largely unchanged from previous epoch")
        }

        return insights
    }
}
```

**Step 2: Run ktlint to verify formatting**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel test //cli:ktlint`
Expected: PASS

**Step 3: Commit**

```bash
git add cli/src/main/kotlin/com/didrikquant/cli/MarkdownRenderer.kt
git commit -m "feat(cli): add MarkdownRenderer for epoch output"
```

---

## Task 4: Create EpochCommand

**Files:**
- Create: `cli/src/main/kotlin/com/didrikquant/cli/EpochCommand.kt`

**Step 1: Create the epoch command that ties everything together**

```kotlin
package com.didrikquant.cli

import com.didrikquant.replay.storage.ChronicleEventStore
import com.didrikquant.replay.storage.RecordedEvent
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

public class EpochCommand(private val dataDir: Path) {

    public fun run(
        fromTimestamp: Long,
        toTimestamp: Long,
        compareFromTimestamp: Long? = null,
        compareToTimestamp: Long? = null,
    ) {
        val currentMetrics = computeMetrics(fromTimestamp, toTimestamp)

        println(MarkdownRenderer.render(currentMetrics))

        if (compareFromTimestamp != null && compareToTimestamp != null) {
            val previousMetrics = computeMetrics(compareFromTimestamp, compareToTimestamp)
            println()
            println(MarkdownRenderer.renderComparison(previousMetrics, currentMetrics))
        }
    }

    private fun computeMetrics(fromTimestamp: Long, toTimestamp: Long): EpochMetrics {
        val store = ChronicleEventStore(dataDir)
        val tailer = store.createTailer()

        val startDate = Instant.ofEpochMilli(fromTimestamp).atZone(ZoneOffset.UTC).toLocalDate()
        tailer.seekToDate(startDate)

        val accumulator = MetricsAccumulator(fromTimestamp, toTimestamp)
        var skippedEvents = 0

        try {
            while (tailer.hasNext()) {
                try {
                    val recorded = tailer.next()
                    if (recorded.eventTimestamp < fromTimestamp) continue
                    if (recorded.eventTimestamp > toTimestamp) break

                    val event = RecordedEvent.toEvent(recorded)
                    accumulator.process(event)
                } catch (e: Exception) {
                    skippedEvents++
                    if (skippedEvents > 100) {
                        System.err.println("Too many corrupted events, stopping")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error reading recordings: ${e.message}")
        }

        tailer.close()
        store.close()

        if (skippedEvents > 0) {
            System.err.println("Warning: Skipped $skippedEvents corrupted events")
        }

        return accumulator.build()
    }
}
```

**Step 2: Run ktlint to verify formatting**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel test //cli:ktlint`
Expected: PASS

**Step 3: Commit**

```bash
git add cli/src/main/kotlin/com/didrikquant/cli/EpochCommand.kt
git commit -m "feat(cli): add EpochCommand for epoch analysis"
```

---

## Task 5: Update Main.kt

**Files:**
- Modify: `cli/src/main/kotlin/com/didrikquant/cli/Main.kt`

**Step 1: Replace entire Main.kt with epoch-only logic**

```kotlin
package com.didrikquant.cli

import java.nio.file.Path

public fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    val dataDir = Path.of(
        System.getenv("REPLAY_DATA_DIR") ?: "${System.getProperty("user.home")}/.dq/recordings",
    )

    when (args[0]) {
        "epoch" -> {
            val fromIndex = args.indexOfFirst { it == "--from" }
            val toIndex = args.indexOfFirst { it == "--to" }

            if (fromIndex == -1 || fromIndex + 1 >= args.size) {
                println("Error: --from <timestamp> is required")
                printUsage()
                return
            }
            if (toIndex == -1 || toIndex + 1 >= args.size) {
                println("Error: --to <timestamp> is required")
                printUsage()
                return
            }

            val fromTimestamp = args[fromIndex + 1].toLongOrNull()
            val toTimestamp = args[toIndex + 1].toLongOrNull()

            if (fromTimestamp == null) {
                println("Invalid --from timestamp: ${args[fromIndex + 1]}")
                return
            }
            if (toTimestamp == null) {
                println("Invalid --to timestamp: ${args[toIndex + 1]}")
                return
            }

            val compareFromIndex = args.indexOfFirst { it == "--compare-from" }
            val compareToIndex = args.indexOfFirst { it == "--compare-to" }

            val compareFromTimestamp = if (compareFromIndex != -1 && compareFromIndex + 1 < args.size) {
                args[compareFromIndex + 1].toLongOrNull()
            } else null

            val compareToTimestamp = if (compareToIndex != -1 && compareToIndex + 1 < args.size) {
                args[compareToIndex + 1].toLongOrNull()
            } else null

            EpochCommand(dataDir).run(
                fromTimestamp = fromTimestamp,
                toTimestamp = toTimestamp,
                compareFromTimestamp = compareFromTimestamp,
                compareToTimestamp = compareToTimestamp,
            )
        }
        else -> {
            println("Unknown command: ${args[0]}")
            printUsage()
        }
    }
}

private fun printUsage() {
    println(
        """
        Usage: dq <command> [options]

        Commands:
          epoch --from <ts> --to <ts>     Analyze epoch metrics
                [--compare-from <ts> --compare-to <ts>]

        Timestamps are epoch milliseconds.

        Environment:
          REPLAY_DATA_DIR    Directory for recorded events (default: ~/.dq/recordings)
        """.trimIndent(),
    )
}
```

**Step 2: Run ktlint to verify formatting**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel test //cli:ktlint`
Expected: PASS

**Step 3: Commit**

```bash
git add cli/src/main/kotlin/com/didrikquant/cli/Main.kt
git commit -m "feat(cli): update Main.kt to use epoch command"
```

---

## Task 6: Delete Old Commands

**Files:**
- Delete: `cli/src/main/kotlin/com/didrikquant/cli/FillsCommand.kt`
- Delete: `cli/src/main/kotlin/com/didrikquant/cli/BookCommand.kt`

**Step 1: Delete the old command files**

```bash
cd /Users/diz/repos/dq/.worktrees/dq-epoch
rm cli/src/main/kotlin/com/didrikquant/cli/FillsCommand.kt
rm cli/src/main/kotlin/com/didrikquant/cli/BookCommand.kt
```

**Step 2: Verify build still works**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel build //cli:dq`
Expected: Build succeeds

**Step 3: Commit**

```bash
git add -u cli/src/main/kotlin/com/didrikquant/cli/
git commit -m "chore(cli): remove obsolete FillsCommand and BookCommand"
```

---

## Task 7: Build and Verify

**Step 1: Run full build**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel build //cli:dq`
Expected: Build succeeds

**Step 2: Run ktlint**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel test //cli:ktlint`
Expected: PASS

**Step 3: Test CLI help output**

Run: `cd /Users/diz/repos/dq/.worktrees/dq-epoch && bazel run //cli:dq`
Expected: Shows usage with epoch command

---

## Task 8: Update Documentation

**Files:**
- Modify: `CLAUDE.md`

**Step 1: Update cli description in CLAUDE.md project structure**

Find the line:
```
- `cli/` - CLI tools (dq fills, dq book)
```

Replace with:
```
- `cli/` - CLI tools (dq epoch)
```

**Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with new CLI command"
```

---

## Notes

**Fee Calculation:** Uses a constant 2 bps taker fee rate. Event.OrderFill doesn't include fees, so this is an approximation.

**Sharpe Calculation:** Computed from mid-price returns during the epoch. This measures market volatility-adjusted performance, not strategy returns directly.

**P&L Calculation:** Uses simple average cost basis. For a market maker with frequent buys/sells, this provides a reasonable approximation.
