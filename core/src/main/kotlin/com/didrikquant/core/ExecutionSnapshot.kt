package com.didrikquant.core

import java.math.BigDecimal

public data class ExecutionSnapshot(
    val position: BigDecimal,
    val realizedPnl: BigDecimal,
    val openOrders: List<TrackedOrder>,
) {
    public val openOrderCount: Int
        get() = openOrders.size

    public fun getOpenOrderBySide(side: Side): TrackedOrder? =
        openOrders.find { it.side == side }
}
