package com.didrikquant.strategy

import com.didrikquant.core.OrderBookSnapshot
import com.didrikquant.core.OrderIntent
import com.didrikquant.core.Side
import com.didrikquant.core.StrategyAction
import com.didrikquant.core.TrackedOrder
import com.didrikquant.core.roundToTick
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

public class AgentXrpStrategy(
    private val minSpreadBps: Int = 4,
    private val spreadBufferBps: Int = 2,
    private val orderSize: BigDecimal = BigDecimal("15"),
    private val skewFactor: BigDecimal = BigDecimal("0.00015"),
    private val tickSize: BigDecimal = BigDecimal("0.00001"),
    private val maxPosition: BigDecimal,
    private val amendThresholdBps: Int = 3,
    private val depthCheckQty: BigDecimal = BigDecimal("100"),
    private val maxSpreadWidenBps: Int = 4,
    private val imbalanceDepthLevels: Int = 5,
    private val imbalanceThreshold: BigDecimal = BigDecimal("0.3"),
    private val imbalanceSpreadWidenBps: Int = 3,
    private val imbalanceSizeReduction: BigDecimal = BigDecimal("0.5"),
) : Strategy {
    override fun onBookSnapshot(
        book: OrderBookSnapshot,
        position: BigDecimal,
        openOrders: List<TrackedOrder>,
    ): List<StrategyAction> {
        if (!book.isValid()) return emptyList()

        val mid = book.midPrice ?: return emptyList()

        val marketSpreadBps = book.spreadBps?.toInt() ?: minSpreadBps
        val baseSpreadBps = maxOf(marketSpreadBps, minSpreadBps)
        val depthAdjustment = calculateDepthAdjustment(book, mid)
        val baseAdaptiveSpreadBps = baseSpreadBps + spreadBufferBps + depthAdjustment

        val imbalance = calculateOrderBookImbalance(book)
        val (bidSpreadAdjustBps, askSpreadAdjustBps) = calculateImbalanceSpreadAdjustment(imbalance)

        val bidSpreadBps = baseAdaptiveSpreadBps + bidSpreadAdjustBps
        val askSpreadBps = baseAdaptiveSpreadBps + askSpreadAdjustBps

        val bidSpreadDecimal = BigDecimal(bidSpreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
        val askSpreadDecimal = BigDecimal(askSpreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)

        val skew = position * skewFactor

        val rawBidPrice = mid - (mid * bidSpreadDecimal) - skew
        val rawAskPrice = mid + (mid * askSpreadDecimal) - skew

        val bidPrice = rawBidPrice.roundToTick(tickSize)
        val askPrice = rawAskPrice.roundToTick(tickSize)

        val rawRatio = position.divide(maxPosition, 8, RoundingMode.HALF_UP)
        val positionRatio = rawRatio.max(-BigDecimal.ONE).min(BigDecimal.ONE)

        val inventoryScaleFactor = BigDecimal("0.8")
        val minSizeAtLimit = BigDecimal("3")

        val longExposure = positionRatio.max(BigDecimal.ZERO)
        val shortExposure = positionRatio.min(BigDecimal.ZERO).abs()

        val (bidSizeMultiplier, askSizeMultiplier) = calculateImbalanceSizeMultiplier(imbalance)

        val rawBidSize = orderSize * (BigDecimal.ONE - longExposure * inventoryScaleFactor) * bidSizeMultiplier
        val bidSize = rawBidSize.setScale(0, RoundingMode.DOWN).max(minSizeAtLimit)
        val rawAskSize = orderSize * (BigDecimal.ONE - shortExposure * inventoryScaleFactor) * askSizeMultiplier
        val askSize = rawAskSize.setScale(0, RoundingMode.DOWN).max(minSizeAtLimit)

        val actions = mutableListOf<StrategyAction>()

        val existingBid = openOrders.find { it.side == Side.BUY }
        val existingAsk = openOrders.find { it.side == Side.SELL }

        actions.addAll(manageOrder(existingBid, Side.BUY, bidPrice, bidSize, mid))
        actions.addAll(manageOrder(existingAsk, Side.SELL, askPrice, askSize, mid))

        if (actions.isNotEmpty() || openOrders.isEmpty()) {
            logger.info {
                "mid=$mid spread=${book.spreadBps}bps pos=$position | " +
                    "bid=$bidPrice x $bidSize, ask=$askPrice x $askSize | " +
                    "orders=${openOrders.size} actions=${actions.size}"
            }
        }

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
            logger.debug { "$side: placing $targetPrice x $targetSize" }
            return listOf(StrategyAction.Place(OrderIntent(side, targetPrice, targetSize)))
        }

        if (isOrderCrossed(existing, mid)) {
            logger.info { "$side: CANCEL crossed order ${existing.price} vs mid $mid" }
            return listOf(StrategyAction.Cancel(existing.orderId))
        }

        if (existing.isPartiallyFilled()) {
            logger.debug { "$side: holding partially filled order" }
            return emptyList()
        }

        val driftBps = priceDriftBps(existing.price, targetPrice, mid)

        if (driftBps > amendThresholdBps) {
            logger.info { "$side: AMEND ${existing.price} -> $targetPrice (${driftBps}bps drift)" }
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

    private fun calculateDepthAdjustment(
        book: OrderBookSnapshot,
        mid: BigDecimal,
    ): Int {
        val topBids = book.topBids(5)
        val topAsks = book.topAsks(5)

        val bidDepth = topBids.sumOf { it.qty }
        val askDepth = topAsks.sumOf { it.qty }
        val minDepth = bidDepth.min(askDepth)

        if (minDepth >= depthCheckQty) return 0

        val depthRatio = minDepth.divide(depthCheckQty, 8, RoundingMode.HALF_UP)
        val widenFactor = BigDecimal.ONE - depthRatio
        return widenFactor.multiply(BigDecimal(maxSpreadWidenBps)).toInt()
    }

    private fun calculateOrderBookImbalance(book: OrderBookSnapshot): BigDecimal {
        val topBids = book.topBids(imbalanceDepthLevels)
        val topAsks = book.topAsks(imbalanceDepthLevels)

        val bidVolume = topBids.sumOf { it.qty }
        val askVolume = topAsks.sumOf { it.qty }
        val totalVolume = bidVolume + askVolume

        if (totalVolume <= BigDecimal.ZERO) return BigDecimal.ZERO

        return (bidVolume - askVolume).divide(totalVolume, 8, RoundingMode.HALF_UP)
    }

    private fun calculateImbalanceSpreadAdjustment(imbalance: BigDecimal): Pair<Int, Int> {
        val absImbalance = imbalance.abs()
        if (absImbalance < imbalanceThreshold) return Pair(0, 0)

        val adjustmentBps = absImbalance
            .subtract(imbalanceThreshold)
            .divide(BigDecimal.ONE - imbalanceThreshold, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal(imbalanceSpreadWidenBps))
            .toInt()

        return if (imbalance > BigDecimal.ZERO) {
            Pair(0, adjustmentBps)
        } else {
            Pair(adjustmentBps, 0)
        }
    }

    private fun calculateImbalanceSizeMultiplier(imbalance: BigDecimal): Pair<BigDecimal, BigDecimal> {
        val absImbalance = imbalance.abs()
        if (absImbalance < imbalanceThreshold) return Pair(BigDecimal.ONE, BigDecimal.ONE)

        val reductionFactor = absImbalance
            .subtract(imbalanceThreshold)
            .divide(BigDecimal.ONE - imbalanceThreshold, 8, RoundingMode.HALF_UP)
            .multiply(imbalanceSizeReduction)

        val reducedMultiplier = BigDecimal.ONE - reductionFactor

        return if (imbalance > BigDecimal.ZERO) {
            Pair(BigDecimal.ONE, reducedMultiplier)
        } else {
            Pair(reducedMultiplier, BigDecimal.ONE)
        }
    }
}
