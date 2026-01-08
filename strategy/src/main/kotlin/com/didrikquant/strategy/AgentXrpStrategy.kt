package com.didrikquant.strategy

import com.didrikquant.core.OrderBook
import com.didrikquant.core.Side
import com.didrikquant.core.roundToTick
import java.math.BigDecimal
import java.math.RoundingMode

public class AgentXrpStrategy(
    private val spreadBps: Int = 8,
    private val orderSize: BigDecimal = BigDecimal("15"),
    private val skewFactor: BigDecimal = BigDecimal("0.00015"),
    private val tickSize: BigDecimal = BigDecimal("0.00001"),
    private val maxPosition: BigDecimal = BigDecimal("75"),
) : Strategy {

    override fun onOrderBook(book: OrderBook, position: BigDecimal): List<OrderIntent> {
        if (!book.isValid()) return emptyList()

        val mid = book.midPrice ?: return emptyList()

        val halfSpread = mid * BigDecimal(spreadBps).divide(
            BigDecimal("20000"),
            8,
            RoundingMode.HALF_UP,
        )

        val skew = position * skewFactor

        val rawBidPrice = mid - halfSpread - skew
        val rawAskPrice = mid + halfSpread - skew

        val bidPrice = rawBidPrice.roundToTick(tickSize)
        val askPrice = rawAskPrice.roundToTick(tickSize)

        val positionRatio = position.divide(maxPosition, 8, RoundingMode.HALF_UP)
            .max(-BigDecimal.ONE).min(BigDecimal.ONE)

        val inventoryScaleFactor = BigDecimal("0.8")
        val minSizeAtLimit = BigDecimal("3")

        val longExposure = positionRatio.max(BigDecimal.ZERO)
        val shortExposure = positionRatio.min(BigDecimal.ZERO).abs()

        val bidSize = (orderSize * (BigDecimal.ONE - longExposure * inventoryScaleFactor))
            .setScale(0, RoundingMode.DOWN).max(minSizeAtLimit)
        val askSize = (orderSize * (BigDecimal.ONE - shortExposure * inventoryScaleFactor))
            .setScale(0, RoundingMode.DOWN).max(minSizeAtLimit)

        return listOf(
            OrderIntent(Side.BUY, bidPrice, bidSize),
            OrderIntent(Side.SELL, askPrice, askSize),
        )
    }
}
