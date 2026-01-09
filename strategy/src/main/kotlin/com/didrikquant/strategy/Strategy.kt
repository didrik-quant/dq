package com.didrikquant.strategy

import com.didrikquant.core.OrderBookSnapshot
import com.didrikquant.core.StrategyAction
import com.didrikquant.orderstate.OrderSnapshot
import java.math.BigDecimal

public interface Strategy {
    public fun onBookSnapshot(
        book: OrderBookSnapshot,
        position: BigDecimal,
        openOrders: List<OrderSnapshot>,
    ): List<StrategyAction>
}
