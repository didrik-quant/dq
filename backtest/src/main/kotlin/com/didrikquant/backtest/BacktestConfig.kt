package com.didrikquant.backtest

import com.didrikquant.replay.player.ReplayConfig
import com.didrikquant.risk.RiskConfig
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

public data class BacktestConfig(
    val dataDir: Path,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val symbol: String,
    val spreadBps: Int = 10,
    val orderSize: BigDecimal = BigDecimal("10"),
    val tickSize: BigDecimal = BigDecimal("0.0001"),
    val requoteIntervalMs: Long = 2000,
    val riskConfig: RiskConfig = RiskConfig(),
    val feeRate: BigDecimal = BigDecimal("0.0002"),
) {
    public fun toReplayConfig(): ReplayConfig = ReplayConfig(
        dataDir = dataDir,
        startDate = startDate,
        endDate = endDate,
        symbolFilter = symbol,
    )

    public companion object {
        private const val DEFAULT_SUBDIR: String = ".dq/recordings"

        public fun default(
            startDate: LocalDate = LocalDate.now().minusDays(7),
            endDate: LocalDate = LocalDate.now().minusDays(1),
        ): BacktestConfig {
            val home = System.getProperty("user.home")
            return BacktestConfig(
                dataDir = Path.of(home, DEFAULT_SUBDIR),
                startDate = startDate,
                endDate = endDate,
                symbol = "PF_XRPUSD",
            )
        }

        public fun fromEnv(): BacktestConfig {
            val dataDir = System.getenv("REPLAY_DATA_DIR")
                ?: "${System.getProperty("user.home")}/$DEFAULT_SUBDIR"
            val startDays = System.getenv("BACKTEST_START_DAYS")?.toLongOrNull() ?: 7
            val endDays = System.getenv("BACKTEST_END_DAYS")?.toLongOrNull() ?: 1

            return BacktestConfig(
                dataDir = Path.of(dataDir),
                startDate = LocalDate.now().minusDays(startDays),
                endDate = LocalDate.now().minusDays(endDays),
                symbol = System.getenv("BACKTEST_SYMBOL") ?: "PF_XRPUSD",
            )
        }
    }
}
