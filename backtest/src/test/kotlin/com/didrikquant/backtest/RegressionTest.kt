package com.didrikquant.backtest

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertTrue

@Tag("regression")
internal class RegressionTest {

    private companion object {
        private val DATA_DIR: Path = Path.of(
            System.getenv("REPLAY_DATA_DIR")
                ?: "${System.getProperty("user.home")}/.dq/recordings",
        )

        @JvmStatic
        @BeforeAll
        fun checkDataAvailable() {
            val hasData = Files.exists(DATA_DIR) &&
                Files.list(DATA_DIR).use { it.findAny().isPresent }

            Assumptions.assumeTrue(
                hasData,
                "No replay data available at $DATA_DIR - skipping regression tests",
            )
        }
    }

    @Test
    fun `strategy produces reasonable PnL on last 7 days`() {
        val config = BacktestConfig(
            dataDir = DATA_DIR,
            startDate = LocalDate.now().minusDays(7),
            endDate = LocalDate.now().minusDays(1),
            symbol = "PF_XRPUSD",
            spreadBps = 10,
            orderSize = BigDecimal("10"),
        )

        val result = BacktestRunner(config).run()

        assertTrue(
            result.pnl.totalPnL >= BigDecimal("-50"),
            "Strategy P&L should be reasonable (not catastrophic loss): ${result.pnl.totalPnL}",
        )

        println(result.toReport().toSummary())
    }

    @Test
    fun `strategy survives 30 days without catastrophic loss`() {
        val config = BacktestConfig(
            dataDir = DATA_DIR,
            startDate = LocalDate.now().minusDays(30),
            endDate = LocalDate.now().minusDays(1),
            symbol = "PF_XRPUSD",
            spreadBps = 10,
            orderSize = BigDecimal("10"),
        )

        val result = BacktestRunner(config).run()

        assertTrue(
            result.pnl.totalPnL > BigDecimal("-200"),
            "Max loss exceeded over 30 days: ${result.pnl.totalPnL}",
        )

        println(result.toReport().toSummary())
    }

    @Test
    fun `pipeline processes events without exceptions`() {
        val config = BacktestConfig(
            dataDir = DATA_DIR,
            startDate = LocalDate.now().minusDays(3),
            endDate = LocalDate.now().minusDays(1),
            symbol = "PF_XRPUSD",
        )

        assertDoesNotThrow {
            BacktestRunner(config).run()
        }
    }

    @Test
    fun `position stays within risk limits`() {
        val maxPosition = BigDecimal("100")
        val config = BacktestConfig(
            dataDir = DATA_DIR,
            startDate = LocalDate.now().minusDays(7),
            endDate = LocalDate.now().minusDays(1),
            symbol = "PF_XRPUSD",
            spreadBps = 10,
            orderSize = BigDecimal("10"),
            riskConfig = com.didrikquant.risk.RiskConfig(maxPositionSize = maxPosition),
        )

        val result = BacktestRunner(config).run()

        assertTrue(
            result.metrics.maxPosition <= maxPosition,
            "Max position ${result.metrics.maxPosition} exceeded limit $maxPosition",
        )

        assertTrue(
            result.metrics.minPosition >= maxPosition.negate(),
            "Min position ${result.metrics.minPosition} exceeded limit ${maxPosition.negate()}",
        )
    }
}
