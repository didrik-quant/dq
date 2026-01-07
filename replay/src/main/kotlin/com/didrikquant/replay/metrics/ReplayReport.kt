package com.didrikquant.replay.metrics

import com.didrikquant.replay.player.ReplayStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal

@Serializable
public data class ReplayReport(
    val eventCount: Long,
    val elapsedMs: Long,
    val eventsPerSecond: Long,
    val firstEventTimestamp: Long,
    val lastEventTimestamp: Long,
    val bookSnapshotCount: Long,
    val bookUpdateCount: Long,
    val orderAcceptedCount: Int,
    val orderFillCount: Int,
    val orderCanceledCount: Int,
    val orderRejectedCount: Int,
    val position: String,
    val realizedPnL: String,
    val unrealizedPnL: String,
    val totalPnL: String,
    val totalFees: String,
    val totalVolume: String,
    val fillCount: Int,
    val maxPosition: String,
    val minPosition: String,
) {
    public companion object {
        private val json = Json { prettyPrint = true }

        public fun create(
            replayStats: ReplayStats,
            metrics: MetricsSnapshot,
            pnl: PnLSnapshot,
        ): ReplayReport = ReplayReport(
            eventCount = replayStats.eventCount,
            elapsedMs = replayStats.elapsedMs,
            eventsPerSecond = replayStats.eventsPerSecond,
            firstEventTimestamp = replayStats.firstEventTimestamp,
            lastEventTimestamp = replayStats.lastEventTimestamp,
            bookSnapshotCount = metrics.bookSnapshotCount,
            bookUpdateCount = metrics.bookUpdateCount,
            orderAcceptedCount = metrics.orderAcceptedCount,
            orderFillCount = metrics.orderFillCount,
            orderCanceledCount = metrics.orderCanceledCount,
            orderRejectedCount = metrics.orderRejectedCount,
            position = pnl.position.toPlainString(),
            realizedPnL = pnl.realizedPnL.toPlainString(),
            unrealizedPnL = pnl.unrealizedPnL.toPlainString(),
            totalPnL = pnl.totalPnL.toPlainString(),
            totalFees = pnl.totalFees.toPlainString(),
            totalVolume = pnl.totalVolume.toPlainString(),
            fillCount = pnl.fillCount,
            maxPosition = metrics.maxPosition.toPlainString(),
            minPosition = metrics.minPosition.toPlainString(),
        )
    }

    public fun toJson(): String = json.encodeToString(this)

    public fun toSummary(): String = buildString {
        appendLine("=== Replay Report ===")
        appendLine("Events: $eventCount in ${elapsedMs}ms ($eventsPerSecond/sec)")
        appendLine("Book Updates: $bookUpdateCount (snapshots: $bookSnapshotCount)")
        appendLine("Orders: accepted=$orderAcceptedCount, filled=$orderFillCount, canceled=$orderCanceledCount")
        appendLine()
        appendLine("=== P&L ===")
        appendLine("Position: $position")
        appendLine("Realized P&L: $realizedPnL")
        appendLine("Unrealized P&L: $unrealizedPnL")
        appendLine("Total P&L: $totalPnL")
        appendLine("Total Fees: $totalFees")
        appendLine("Total Volume: $totalVolume")
        appendLine("Fill Count: $fillCount")
        appendLine("Position Range: [$minPosition, $maxPosition]")
    }
}
