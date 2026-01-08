package com.didrikquant.bot

import com.didrikquant.risk.RiskConfig
import java.math.BigDecimal

public data class BotConfig(
    val symbol: String = "PF_XRPUSD",
    val spreadBps: Int = 10,
    val orderSize: BigDecimal = BigDecimal("10"),
    val maxPosition: BigDecimal = BigDecimal("100"),
    val tickSize: BigDecimal = BigDecimal("0.0001"),
    val riskConfig: RiskConfig = RiskConfig(),
    val requoteIntervalMs: Long = 0,
    val dryRun: Boolean = false,
    val epochTradeCount: Int? = null,
    val epochMaxDurationMs: Long? = null,
    val strategyClass: String = "SimpleMarketMaker",
)
