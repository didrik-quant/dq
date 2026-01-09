package com.didrikquant.cli

import java.math.RoundingMode
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
        appendLine("| Realized P&L | \$${metrics.realizedPnl.setScale(2, RoundingMode.HALF_UP)} |")
        appendLine("| Fees Paid | \$${metrics.feesPaid.setScale(2, RoundingMode.HALF_UP)} |")
        appendLine("| Net P&L | \$${metrics.netPnl.setScale(2, RoundingMode.HALF_UP)} |")
        val avgVsMid = metrics.avgFillPriceVsMidBps?.setScale(1, RoundingMode.HALF_UP)?.let { "$it bps" } ?: "N/A"
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
        appendLine("| Max Long Position | ${metrics.maxLongPosition.setScale(2, RoundingMode.HALF_UP)} |")
        appendLine("| Max Short Position | ${metrics.maxShortPosition.setScale(2, RoundingMode.HALF_UP)} |")
        appendLine("| Avg Inventory | ${metrics.avgInventory.setScale(2, RoundingMode.HALF_UP)} |")
        appendLine("| Max Drawdown | \$${metrics.maxDrawdown.setScale(2, RoundingMode.HALF_UP)} |")
        val sharpeStr = metrics.sharpe?.setScale(2, RoundingMode.HALF_UP)?.toString() ?: "N/A"
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
            "\$${previous.netPnl.setScale(2, RoundingMode.HALF_UP)}",
            "\$${current.netPnl.setScale(2, RoundingMode.HALF_UP)}",
            formatDelta(current.netPnl - previous.netPnl, prefix = "\$"),
        )

        val prevSharpe = previous.sharpe?.setScale(2, RoundingMode.HALF_UP)?.toString() ?: "N/A"
        val currSharpe = current.sharpe?.setScale(2, RoundingMode.HALF_UP)?.toString() ?: "N/A"
        val sharpeDelta = if (previous.sharpe != null && current.sharpe != null) {
            formatDelta(current.sharpe - previous.sharpe)
        } else {
            "N/A"
        }
        row("Sharpe", prevSharpe, currSharpe, sharpeDelta)

        row(
            "Total Fills",
            previous.totalFills.toString(),
            current.totalFills.toString(),
            formatDelta(current.totalFills - previous.totalFills),
        )

        row(
            "Avg Inventory",
            previous.avgInventory.setScale(2, RoundingMode.HALF_UP).toString(),
            current.avgInventory.setScale(2, RoundingMode.HALF_UP).toString(),
            formatDelta(current.avgInventory - previous.avgInventory),
        )

        row(
            "Max Drawdown",
            "\$${previous.maxDrawdown.setScale(2, RoundingMode.HALF_UP)}",
            "\$${current.maxDrawdown.setScale(2, RoundingMode.HALF_UP)}",
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
            is java.math.BigDecimal -> "$sign$prefix${value.setScale(2, RoundingMode.HALF_UP)}"
            else -> "$sign$prefix$value"
        }
    }

    private fun generateInterpretation(previous: EpochMetrics, current: EpochMetrics): List<String> {
        val insights = mutableListOf<String>()

        if (current.netPnl > previous.netPnl) {
            insights.add("P&L improved by \$${(current.netPnl - previous.netPnl).setScale(2, RoundingMode.HALF_UP)}")
        } else if (current.netPnl < previous.netPnl) {
            insights.add("P&L decreased by \$${(previous.netPnl - current.netPnl).setScale(2, RoundingMode.HALF_UP)}")
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
