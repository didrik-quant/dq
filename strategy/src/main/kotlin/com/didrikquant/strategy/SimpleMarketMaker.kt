package com.didrikquant.strategy

import com.didrikquant.core.*
import java.math.BigDecimal
import java.math.RoundingMode

public class SimpleMarketMaker(
    private val spreadBps: Int = 10,
    private val orderSize: BigDecimal = BigDecimal("15"),
    private val skewFactor: BigDecimal = BigDecimal("0.0001"),
    private val tickSize: BigDecimal = BigDecimal("0.00001"),
) : Strategy {

    override fun onOrderBook(book: OrderBook, position: BigDecimal): List<OrderIntent> {
        if (!book.isValid()) return emptyList()

        val mid = book.midPrice ?: return emptyList()

        // Half spread in decimal form (10 bps = 0.001, so half = 0.0005)
        val halfSpread = mid * BigDecimal(spreadBps).divide(
            BigDecimal("20000"),
            8,
            RoundingMode.HALF_UP,
        )

        // Skew based on position: positive position -> lower bid, higher ask
        val skew = position * skewFactor

        val rawBidPrice = mid - halfSpread - skew
        val rawAskPrice = mid + halfSpread - skew

        val bidPrice = rawBidPrice.roundToTick(tickSize)
        val askPrice = rawAskPrice.roundToTick(tickSize)

        return listOf(
            OrderIntent(Side.BUY, bidPrice, orderSize),
            OrderIntent(Side.SELL, askPrice, orderSize),
        )
    }
}
