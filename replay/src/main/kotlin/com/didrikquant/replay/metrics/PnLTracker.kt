package com.didrikquant.replay.metrics

import com.didrikquant.orderstate.Side
import java.math.BigDecimal
import java.math.RoundingMode

public data class PnLSnapshot(
    val position: BigDecimal,
    val realizedPnL: BigDecimal,
    val unrealizedPnL: BigDecimal,
    val totalPnL: BigDecimal,
    val totalFees: BigDecimal,
    val totalVolume: BigDecimal,
    val fillCount: Int,
    val avgEntryPrice: BigDecimal,
)

public data class Fill(
    val symbol: String,
    val side: Side,
    val fillQty: BigDecimal,
    val fillPrice: BigDecimal,
)

public class PnLTracker(
    private val symbol: String,
    private val feeRate: BigDecimal = BigDecimal("0.0002"),
) {
    private var position: BigDecimal = BigDecimal.ZERO
    private var realizedPnL: BigDecimal = BigDecimal.ZERO
    private var costBasis: BigDecimal = BigDecimal.ZERO
    private var totalFees: BigDecimal = BigDecimal.ZERO
    private var totalVolume: BigDecimal = BigDecimal.ZERO
    private var fillCount: Int = 0

    public fun onFill(fill: Fill) {
        if (fill.symbol != symbol) return

        val notional = fill.fillQty * fill.fillPrice
        val fee = notional * feeRate
        totalFees += fee
        totalVolume += notional
        fillCount++

        when (fill.side) {
            Side.BUY -> processBuy(fill.fillQty, fill.fillPrice)
            Side.SELL -> processSell(fill.fillQty, fill.fillPrice)
        }
    }

    private fun processBuy(qty: BigDecimal, price: BigDecimal) {
        if (position < BigDecimal.ZERO) {
            val closingQty = minOf(qty, position.abs())
            val avgShortPrice = if (position != BigDecimal.ZERO) {
                costBasis.divide(position.abs(), 8, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
            realizedPnL += closingQty * (avgShortPrice - price)

            val remainingQty = qty - closingQty
            if (remainingQty > BigDecimal.ZERO) {
                costBasis = remainingQty * price
                position = remainingQty
            } else {
                position += closingQty
                if (position != BigDecimal.ZERO) {
                    val ratio = BigDecimal.ONE - closingQty.divide(
                        position.abs() + closingQty,
                        8,
                        RoundingMode.HALF_UP,
                    )
                    costBasis *= ratio
                } else {
                    costBasis = BigDecimal.ZERO
                }
            }
        } else {
            costBasis += qty * price
            position += qty
        }
    }

    private fun processSell(qty: BigDecimal, price: BigDecimal) {
        if (position > BigDecimal.ZERO) {
            val closingQty = minOf(qty, position)
            val avgLongPrice = costBasis.divide(position, 8, RoundingMode.HALF_UP)
            realizedPnL += closingQty * (price - avgLongPrice)

            val remainingQty = qty - closingQty
            if (remainingQty > BigDecimal.ZERO) {
                costBasis = remainingQty * price
                position = -remainingQty
            } else {
                position -= closingQty
                if (position > BigDecimal.ZERO) {
                    val ratio = BigDecimal.ONE - closingQty.divide(
                        position + closingQty,
                        8,
                        RoundingMode.HALF_UP,
                    )
                    costBasis *= ratio
                } else {
                    costBasis = BigDecimal.ZERO
                }
            }
        } else {
            costBasis += qty * price
            position -= qty
        }
    }

    public fun unrealizedPnL(currentMid: BigDecimal): BigDecimal {
        if (position == BigDecimal.ZERO) return BigDecimal.ZERO
        val avgPrice = costBasis.divide(position.abs(), 8, RoundingMode.HALF_UP)
        return if (position > BigDecimal.ZERO) {
            position * (currentMid - avgPrice)
        } else {
            position.abs() * (avgPrice - currentMid)
        }
    }

    public fun snapshot(currentMid: BigDecimal): PnLSnapshot {
        val unrealized = unrealizedPnL(currentMid)
        val avgEntry = if (position != BigDecimal.ZERO) {
            costBasis.divide(position.abs(), 8, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        return PnLSnapshot(
            position = position,
            realizedPnL = realizedPnL,
            unrealizedPnL = unrealized,
            totalPnL = realizedPnL + unrealized - totalFees,
            totalFees = totalFees,
            totalVolume = totalVolume,
            fillCount = fillCount,
            avgEntryPrice = avgEntry,
        )
    }

    public fun reset() {
        position = BigDecimal.ZERO
        realizedPnL = BigDecimal.ZERO
        costBasis = BigDecimal.ZERO
        totalFees = BigDecimal.ZERO
        totalVolume = BigDecimal.ZERO
        fillCount = 0
    }
}
