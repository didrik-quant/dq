package com.didrikquant.risk

import java.math.BigDecimal

public data class RiskConfig(
    val maxPositionSize: BigDecimal = BigDecimal("100"),
    val maxOrderSize: BigDecimal = BigDecimal("10"),
    val maxOpenOrders: Int = 10,
    val maxLossUsd: BigDecimal = BigDecimal("25"),
    val minOrderSize: BigDecimal = BigDecimal("1"),
)
