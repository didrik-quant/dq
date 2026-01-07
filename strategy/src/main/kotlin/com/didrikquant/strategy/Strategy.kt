package com.didrikquant.strategy

import com.didrikquant.core.OrderBook
import java.math.BigDecimal

public interface Strategy {
    public fun onOrderBook(book: OrderBook, position: BigDecimal): List<OrderIntent>
}
