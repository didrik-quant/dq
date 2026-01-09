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
