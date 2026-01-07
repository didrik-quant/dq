package com.didrikquant.replay.simulator

import com.didrikquant.core.OrderBook
import com.didrikquant.core.Side

public class SimpleFillModel {

    public fun checkFills(
        pendingOrders: Collection<SimulatedOrder>,
        book: OrderBook,
        currentTimestamp: Long,
    ): List<SimulatedFill> {
        if (!book.isValid()) return emptyList()

        val fills = mutableListOf<SimulatedFill>()
        val bestBid = book.bestBid!!
        val bestAsk = book.bestAsk!!

        for (order in pendingOrders) {
            val fill = when (order.side) {
                Side.BUY -> {
                    if (bestAsk <= order.price) {
                        SimulatedFill(
                            order = order,
                            fillPrice = bestAsk,
                            fillQty = order.qty,
                            timestamp = currentTimestamp,
                        )
                    } else {
                        null
                    }
                }
                Side.SELL -> {
                    if (bestBid >= order.price) {
                        SimulatedFill(
                            order = order,
                            fillPrice = bestBid,
                            fillQty = order.qty,
                            timestamp = currentTimestamp,
                        )
                    } else {
                        null
                    }
                }
            }
            fill?.let { fills.add(it) }
        }

        return fills
    }
}
