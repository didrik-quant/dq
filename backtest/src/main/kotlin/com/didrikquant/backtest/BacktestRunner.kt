package com.didrikquant.backtest

import com.didrikquant.replay.player.EventPlayer
import com.didrikquant.replay.storage.ChronicleEventStore
import mu.KotlinLogging
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

public class BacktestRunner(private val config: BacktestConfig) {

    public fun run(): BacktestResult {
        logger.info { "Starting backtest: ${config.startDate} to ${config.endDate} for ${config.symbol}" }

        val store = ChronicleEventStore(config.dataDir)
        val pipeline = BacktestPipeline(config)
        val disruptor = pipeline.start()
        val ringBuffer = disruptor.ringBuffer

        val player = EventPlayer(store, ringBuffer, config.toReplayConfig())

        val startTime = System.currentTimeMillis()
        val replayStats = player.replay()
        val elapsedMs = System.currentTimeMillis() - startTime

        val finalMid = pipeline.orderBook.midPrice ?: BigDecimal.ZERO
        val pnlSnapshot = pipeline.pnlTracker.snapshot(finalMid)
        val metricsSnapshot = pipeline.metrics.snapshot()

        pipeline.stop()
        store.close()

        logger.info {
            "Backtest complete: ${replayStats.eventCount} events in ${elapsedMs}ms " +
                "(${replayStats.eventsPerSecond} events/sec)"
        }
        logger.info { "P&L: ${pnlSnapshot.totalPnL}, Fills: ${pnlSnapshot.fillCount}" }

        return BacktestResult(
            config = config,
            pnl = pnlSnapshot,
            metrics = metricsSnapshot,
            replayStats = replayStats,
            elapsedMs = elapsedMs,
        )
    }
}
