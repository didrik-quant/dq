package com.didrikquant.strategy

import com.didrikquant.core.OrderBook
import com.didrikquant.core.StrategyAction
import com.didrikquant.core.TrackedOrder
import java.math.BigDecimal

public interface Strategy {
    public fun onOrderBook(
        book: OrderBook,
        position: BigDecimal,
        openOrders: List<TrackedOrder>,
    ): List<StrategyAction>
}
