package com.didrikquant.core

import java.math.BigDecimal

public data class PositionSnapshot(
    val position: BigDecimal,
    val realizedPnl: BigDecimal,
    val avgEntryPrice: BigDecimal?,
)
