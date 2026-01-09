package com.didrikquant.replay.simulator

import com.didrikquant.orderstate.Side
import java.math.BigDecimal

public data class SimulatedOrder(
    val orderId: String,
    val clOrdId: String,
    val symbol: String,
    val side: Side,
    val price: BigDecimal,
    val qty: BigDecimal,
    val createdAt: Long,
)

public data class SimulatedFill(
    val order: SimulatedOrder,
    val fillPrice: BigDecimal,
    val fillQty: BigDecimal,
    val timestamp: Long,
)
