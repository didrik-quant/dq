package com.didrikquant.cli

import com.didrikquant.core.Event
import com.didrikquant.core.OrderBook
import com.didrikquant.core.ZERO
import com.didrikquant.core.safeDivide
import com.didrikquant.orderstate.Side
import java.math.BigDecimal
import kotlin.math.sqrt

private val FEE_RATE = BigDecimal("0.0002") // 2 bps taker fee

public class MetricsAccumulator(
    private val startTimestamp: Long,
    private val endTimestamp: Long,
) {
    private var orderBook: OrderBook? = null
    private var sequence: Long = 0

    private var totalFills = 0
    private var buyFills = 0
    private var sellFills = 0

    private var realizedPnl = ZERO
    private var feesPaid = ZERO
    private var position = ZERO
    private var costBasis = ZERO

    private var maxLongPosition = ZERO
    private var maxShortPosition = ZERO
    private var inventorySum = ZERO
    private var inventoryCount = 0L

    private var peakPnl = ZERO
    private var maxDrawdown = ZERO

    private var fillPriceVsMidSum = ZERO
    private var fillPriceVsMidCount = 0

    private val returns = mutableListOf<BigDecimal>()
    private var lastMidPrice: BigDecimal? = null

    public fun process(event: Event) {
        when (event) {
            is Event.BookSnapshot -> {
                if (orderBook == null || orderBook?.symbol != event.symbol) {
                    orderBook = OrderBook(event.symbol)
                }
                orderBook?.applySnapshot(event.bids, event.asks, sequence++)
                trackReturn()
            }
            is Event.BookUpdate -> {
                orderBook?.applyUpdate(event.bids, event.asks, sequence++)
                trackReturn()
            }
            is Event.OrderFill -> processFill(event)
            else -> {}
        }
    }

    private fun trackReturn() {
        val mid = orderBook?.midPrice ?: return
        val last = lastMidPrice
        if (last != null && last != ZERO) {
            val ret = (mid - last).safeDivide(last)
            returns.add(ret)
        }
        lastMidPrice = mid
    }

    private fun processFill(fill: Event.OrderFill) {
        totalFills++
        val fillValue = fill.fillPrice * fill.fillQty
        val fee = fillValue * FEE_RATE
        feesPaid += fee

        val midAtFill = orderBook?.midPrice
        if (midAtFill != null && midAtFill != ZERO) {
            val slippageBps = (fill.fillPrice - midAtFill).safeDivide(midAtFill) * BigDecimal("10000")
            fillPriceVsMidSum += if (fill.side == Side.BUY) slippageBps else -slippageBps
            fillPriceVsMidCount++
        }

        when (fill.side) {
            Side.BUY -> {
                buyFills++
                position += fill.fillQty
                costBasis += fillValue
            }
            Side.SELL -> {
                sellFills++
                val avgCost = if (position != ZERO) costBasis.safeDivide(position) else ZERO
                val pnl = (fill.fillPrice - avgCost) * fill.fillQty
                realizedPnl += pnl
                position -= fill.fillQty
                costBasis -= avgCost * fill.fillQty
            }
        }

        // Track position extremes
        if (position > maxLongPosition) maxLongPosition = position
        if (position < maxShortPosition) maxShortPosition = position
        inventorySum += position.abs()
        inventoryCount++

        // Track drawdown
        val currentPnl = realizedPnl - feesPaid
        if (currentPnl > peakPnl) peakPnl = currentPnl
        val drawdown = peakPnl - currentPnl
        if (drawdown > maxDrawdown) maxDrawdown = drawdown
    }

    public fun build(): EpochMetrics {
        val avgFillVsMid = if (fillPriceVsMidCount > 0) {
            fillPriceVsMidSum.safeDivide(BigDecimal(fillPriceVsMidCount))
        } else {
            null
        }

        val avgInventory = if (inventoryCount > 0) {
            inventorySum.safeDivide(BigDecimal(inventoryCount))
        } else {
            ZERO
        }

        val sharpe = calculateSharpe()

        return EpochMetrics(
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            totalFills = totalFills,
            buyFills = buyFills,
            sellFills = sellFills,
            realizedPnl = realizedPnl,
            feesPaid = feesPaid,
            netPnl = realizedPnl - feesPaid,
            avgFillPriceVsMidBps = avgFillVsMid,
            maxLongPosition = maxLongPosition,
            maxShortPosition = maxShortPosition,
            avgInventory = avgInventory,
            maxDrawdown = maxDrawdown,
            sharpe = sharpe,
        )
    }

    private fun calculateSharpe(): BigDecimal? {
        if (returns.size < 2) return null
        val mean = returns.reduce { a, b -> a + b }.safeDivide(BigDecimal(returns.size))
        val variance = returns.map { (it - mean) * (it - mean) }
            .reduce { a, b -> a + b }
            .safeDivide(BigDecimal(returns.size))
        val stdDev = BigDecimal(sqrt(variance.toDouble()))
        if (stdDev == ZERO) return null
        return mean.safeDivide(stdDev)
    }
}
