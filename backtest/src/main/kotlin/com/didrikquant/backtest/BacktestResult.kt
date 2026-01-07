package com.didrikquant.backtest

import com.didrikquant.replay.metrics.MetricsSnapshot
import com.didrikquant.replay.metrics.PnLSnapshot
import com.didrikquant.replay.metrics.ReplayReport
import com.didrikquant.replay.player.ReplayStats

public data class BacktestResult(
    val config: BacktestConfig,
    val pnl: PnLSnapshot,
    val metrics: MetricsSnapshot,
    val replayStats: ReplayStats,
    val elapsedMs: Long,
) {
    public fun toReport(): ReplayReport = ReplayReport.create(
        replayStats = replayStats,
        metrics = metrics,
        pnl = pnl,
    )
}
