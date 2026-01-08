package com.didrikquant.strategy

import com.didrikquant.core.OrderBook
import com.didrikquant.core.Side
import com.didrikquant.core.TrackedOrder
import com.didrikquant.core.roundToTick
import java.math.BigDecimal
import java.math.RoundingMode

public class AgentXrpStrategy(
    private val spreadBps: Int = 6,
    private val orderSize: BigDecimal = BigDecimal("15"),
    private val skewFactor: BigDecimal = BigDecimal("0.00015"),
    private val tickSize: BigDecimal = BigDecimal("0.00001"),
    private val maxPosition: BigDecimal = BigDecimal("75"),
    private val amendThresholdBps: Int = 3,
) : Strategy {
    override fun onOrderBook(
        book: OrderBook,
        position: BigDecimal,
        openOrders: List<TrackedOrder>,
    ): List<StrategyAction> {
        if (!book.isValid()) return emptyList()

        val mid = book.midPrice ?: return emptyList()

        val spreadDecimal =
            BigDecimal(spreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
        val halfSpread = mid * spreadDecimal

        val skew = position * skewFactor

        val rawBidPrice = mid - halfSpread - skew
        val rawAskPrice = mid + halfSpread - skew

        val bidPrice = rawBidPrice.roundToTick(tickSize)
        val askPrice = rawAskPrice.roundToTick(tickSize)

        val rawRatio = position.divide(maxPosition, 8, RoundingMode.HALF_UP)
        val positionRatio = rawRatio.max(-BigDecimal.ONE).min(BigDecimal.ONE)

        val inventoryScaleFactor = BigDecimal("0.8")
        val minSizeAtLimit = BigDecimal("3")

        val longExposure = positionRatio.max(BigDecimal.ZERO)
        val shortExposure = positionRatio.min(BigDecimal.ZERO).abs()

        val rawBidSize = orderSize * (BigDecimal.ONE - longExposure * inventoryScaleFactor)
        val bidSize = rawBidSize.setScale(0, RoundingMode.DOWN).max(minSizeAtLimit)
        val rawAskSize = orderSize * (BigDecimal.ONE - shortExposure * inventoryScaleFactor)
        val askSize = rawAskSize.setScale(0, RoundingMode.DOWN).max(minSizeAtLimit)

        val actions = mutableListOf<StrategyAction>()

        val existingBid = openOrders.find { it.side == Side.BUY }
        val existingAsk = openOrders.find { it.side == Side.SELL }

        actions.addAll(manageOrder(existingBid, Side.BUY, bidPrice, bidSize, mid))
        actions.addAll(manageOrder(existingAsk, Side.SELL, askPrice, askSize, mid))

        return actions
    }

    private fun manageOrder(
        existing: TrackedOrder?,
        side: Side,
        targetPrice: BigDecimal,
        targetSize: BigDecimal,
        mid: BigDecimal,
    ): List<StrategyAction> {
        if (existing == null) {
            return listOf(StrategyAction.Place(OrderIntent(side, targetPrice, targetSize)))
        }

        if (isOrderCrossed(existing, mid)) {
            return listOf(StrategyAction.Cancel(existing.orderId))
        }

        if (existing.isPartiallyFilled()) {
            return emptyList()
        }

        val driftBps = priceDriftBps(existing.price, targetPrice, mid)

        if (driftBps > amendThresholdBps) {
            return listOf(StrategyAction.Amend(existing.orderId, targetPrice))
        }

        return emptyList()
    }

    private fun isOrderCrossed(
        order: TrackedOrder,
        mid: BigDecimal,
    ): Boolean =
        when (order.side) {
            Side.BUY -> order.price >= mid
            Side.SELL -> order.price <= mid
        }

    private fun priceDriftBps(
        currentPrice: BigDecimal,
        targetPrice: BigDecimal,
        mid: BigDecimal,
    ): Int {
        if (mid <= BigDecimal.ZERO) return 0
        val diff = (currentPrice - targetPrice).abs()
        val bpsDecimal = diff.divide(mid, 8, RoundingMode.HALF_UP)
        return bpsDecimal.multiply(BigDecimal("10000")).toInt()
    }
}
