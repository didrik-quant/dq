package com.didrikquant.harness

import com.didrikquant.core.Event
import com.didrikquant.orderstate.Side
import com.didrikquant.replay.storage.ChronicleEventStore
import com.didrikquant.replay.storage.RecordedEvent
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

public class SharpeCalculator(private val dataDir: Path) {

    private data class Snapshot(
        val timestamp: Instant,
        val position: BigDecimal,
        val mid: BigDecimal,
    )

    public fun calculate(
        startTime: Instant,
        endTime: Instant,
        symbol: String,
        startingEquity: BigDecimal,
    ): BigDecimal? {
        val store = ChronicleEventStore(dataDir)
        val tailer = store.createTailer()

        try {
            val startDate = startTime.atZone(ZoneOffset.UTC).toLocalDate()
            tailer.seekToDate(startDate)

            val fills = mutableListOf<Event.OrderFill>()
            val bookUpdates = mutableListOf<Event.BookUpdate>()
            val bookSnapshots = mutableListOf<Event.BookSnapshot>()

            val startMs = startTime.toEpochMilli()
            val endMs = endTime.toEpochMilli()

            while (tailer.hasNext()) {
                val recorded = tailer.next()
                if (recorded.eventTimestamp < startMs) continue
                if (recorded.eventTimestamp > endMs) break

                try {
                    when (val event = RecordedEvent.toEvent(recorded)) {
                        is Event.OrderFill -> if (event.symbol == symbol) fills.add(event)
                        is Event.BookUpdate -> if (event.symbol == symbol) bookUpdates.add(event)
                        is Event.BookSnapshot -> if (event.symbol == symbol) bookSnapshots.add(event)
                        else -> {}
                    }
                } catch (e: Exception) {
                    logger.debug { "Skipping corrupted event: ${e.message}" }
                }
            }

            logger.info {
                "Loaded ${fills.size} fills, ${bookUpdates.size} book updates, ${bookSnapshots.size} snapshots"
            }

            if (bookUpdates.isEmpty() && bookSnapshots.isEmpty()) {
                logger.warn { "No book data found for Sharpe calculation" }
                return null
            }

            val seconds = generateSequence(startTime) { it.plusSeconds(1) }
                .takeWhile { !it.isAfter(endTime) }
                .toList()

            val snapshots = buildSnapshots(seconds, fills, bookUpdates, bookSnapshots)
            val returns = calculateReturns(snapshots, startingEquity)

            if (returns.size < 300) {
                logger.warn { "Insufficient data for Sharpe: ${returns.size} returns (need 300)" }
                return null
            }

            return computeSharpe(returns)
        } finally {
            tailer.close()
            store.close()
        }
    }

    private fun buildSnapshots(
        seconds: List<Instant>,
        fills: List<Event.OrderFill>,
        bookUpdates: List<Event.BookUpdate>,
        bookSnapshots: List<Event.BookSnapshot>,
    ): List<Snapshot> {
        var position = BigDecimal.ZERO
        var lastMid: BigDecimal? = null
        var fillIndex = 0
        var bookUpdateIndex = 0
        var bookSnapshotIndex = 0

        return seconds.mapNotNull { ts ->
            val tsMillis = ts.toEpochMilli()

            while (fillIndex < fills.size && fills[fillIndex].timestamp <= tsMillis) {
                val fill = fills[fillIndex]
                position = if (fill.side == Side.BUY) {
                    position + fill.fillQty
                } else {
                    position - fill.fillQty
                }
                fillIndex++
            }

            while (bookSnapshotIndex < bookSnapshots.size && bookSnapshots[bookSnapshotIndex].timestamp <= tsMillis) {
                val book = bookSnapshots[bookSnapshotIndex]
                if (book.bids.isNotEmpty() && book.asks.isNotEmpty()) {
                    lastMid = (book.bids.first().price + book.asks.first().price)
                        .divide(BigDecimal(2), MathContext.DECIMAL64)
                }
                bookSnapshotIndex++
            }

            while (bookUpdateIndex < bookUpdates.size && bookUpdates[bookUpdateIndex].timestamp <= tsMillis) {
                val book = bookUpdates[bookUpdateIndex]
                if (book.bids.isNotEmpty() && book.asks.isNotEmpty()) {
                    lastMid = (book.bids.first().price + book.asks.first().price)
                        .divide(BigDecimal(2), MathContext.DECIMAL64)
                }
                bookUpdateIndex++
            }

            lastMid?.let { mid -> Snapshot(ts, position, mid) }
        }
    }

    private fun calculateReturns(
        snapshots: List<Snapshot>,
        startingEquity: BigDecimal,
    ): List<BigDecimal> {
        if (snapshots.size < 2) return emptyList()

        val returns = mutableListOf<BigDecimal>()
        for (i in 1 until snapshots.size) {
            val prev = snapshots[i - 1]
            val curr = snapshots[i]

            // Skip until we have valid book data
            if (prev.mid.compareTo(BigDecimal.ZERO) == 0) continue

            val pnl = prev.position * (curr.mid - prev.mid)
            val ret = pnl.divide(startingEquity, MathContext.DECIMAL64)
            returns.add(ret)
        }
        return returns
    }

    private fun computeSharpe(returns: List<BigDecimal>): BigDecimal? {
        if (returns.isEmpty()) return null

        val mean = returns.fold(BigDecimal.ZERO) { acc, r -> acc + r }
            .divide(BigDecimal(returns.size), MathContext.DECIMAL64)

        val variance = returns.fold(BigDecimal.ZERO) { acc, r ->
            val diff = r - mean
            acc + diff * diff
        }.divide(BigDecimal(returns.size), MathContext.DECIMAL64)

        val stdev = BigDecimal(sqrt(variance.toDouble()))

        if (stdev.compareTo(BigDecimal.ZERO) == 0) {
            logger.warn { "Zero standard deviation, cannot compute Sharpe" }
            return null
        }

        val rawSharpe = mean.divide(stdev, MathContext.DECIMAL64)

        val secondsPerYear = BigDecimal(31_536_000)
        val annualizationFactor = BigDecimal(sqrt(secondsPerYear.toDouble()))

        return (rawSharpe * annualizationFactor).setScale(2, RoundingMode.HALF_UP)
    }
}
