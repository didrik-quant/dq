package com.didrikquant.strategy

import com.didrikquant.core.OrderBookSnapshot
import com.didrikquant.core.OrderIntent
import com.didrikquant.core.Side
import com.didrikquant.core.StrategyAction
import com.didrikquant.core.TrackedOrder
import com.didrikquant.core.roundToTick
import java.math.BigDecimal
import java.math.RoundingMode

@Suppress("unused")
public class SimpleMarketMaker(
    private val spreadBps: Int = 10,
    private val orderSize: BigDecimal = BigDecimal("15"),
    private val skewFactor: BigDecimal = BigDecimal("0.0001"),
    private val tickSize: BigDecimal = BigDecimal("0.00001"),
    private val maxPosition: BigDecimal = BigDecimal("100"),
) : Strategy {
    override fun onBookSnapshot(
        book: OrderBookSnapshot,
        position: BigDecimal,
        openOrders: List<TrackedOrder>,
    ): List<StrategyAction> {
        if (!book.isValid()) return emptyList()

        val mid = book.midPrice ?: return emptyList()

        val spreadDecimal = BigDecimal(spreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
        val halfSpread = mid * spreadDecimal

        val skew = position * skewFactor

        val rawBidPrice = mid - halfSpread - skew
        val rawAskPrice = mid + halfSpread - skew

        val bidPrice = rawBidPrice.roundToTick(tickSize)
        val askPrice = rawAskPrice.roundToTick(tickSize)

        val actions = mutableListOf<StrategyAction>()

        val existingBid = openOrders.find { it.side == Side.BUY }
        val existingAsk = openOrders.find { it.side == Side.SELL }

        if (existingBid == null) {
            actions.add(StrategyAction.Place(OrderIntent(Side.BUY, bidPrice, orderSize)))
        }
        if (existingAsk == null) {
            actions.add(StrategyAction.Place(OrderIntent(Side.SELL, askPrice, orderSize)))
        }

        return actions
    }
}
