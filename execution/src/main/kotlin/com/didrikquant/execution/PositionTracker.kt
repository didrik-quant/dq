package com.didrikquant.execution

import com.didrikquant.core.PositionSnapshot
import com.didrikquant.orderstate.Side
import java.math.BigDecimal
import java.math.RoundingMode

public class PositionTracker {
    private var position: BigDecimal = BigDecimal.ZERO
    private var realizedPnl: BigDecimal = BigDecimal.ZERO
    private var avgEntryPrice: BigDecimal? = null

    public fun getPosition(): BigDecimal = position

    public fun getRealizedPnl(): BigDecimal = realizedPnl

    public fun snapshot(): PositionSnapshot = PositionSnapshot(
        position = position,
        realizedPnl = realizedPnl,
        avgEntryPrice = avgEntryPrice,
    )

    public fun onFill(side: Side, fillQty: BigDecimal, fillPrice: BigDecimal) {
        val currentPosition = position
        val avgEntry = avgEntryPrice

        if (avgEntry != null) {
            val isReducing = when (side) {
                Side.BUY -> currentPosition < BigDecimal.ZERO
                Side.SELL -> currentPosition > BigDecimal.ZERO
            }
            if (isReducing) {
                val pnlPerUnit = when (side) {
                    Side.BUY -> avgEntry - fillPrice
                    Side.SELL -> fillPrice - avgEntry
                }
                val thisPnl = pnlPerUnit * fillQty
                realizedPnl += thisPnl
            }
        }

        val positionDelta = when (side) {
            Side.BUY -> fillQty
            Side.SELL -> fillQty.negate()
        }
        val newPosition = position + positionDelta
        position = newPosition

        updateAvgEntryPrice(currentPosition, newPosition, fillPrice, fillQty, side)
    }

    private fun updateAvgEntryPrice(
        oldPosition: BigDecimal,
        newPosition: BigDecimal,
        fillPrice: BigDecimal,
        fillQty: BigDecimal,
        side: Side,
    ) {
        if (newPosition.signum() == 0) {
            avgEntryPrice = null
            return
        }

        val currentAvg = avgEntryPrice
        if (currentAvg == null) {
            avgEntryPrice = fillPrice
            return
        }

        val wasFlat = oldPosition.signum() == 0
        val sameDirection = oldPosition.signum() == newPosition.signum()

        when {
            wasFlat -> avgEntryPrice = fillPrice
            sameDirection -> {
                val totalValue = (currentAvg * oldPosition.abs()) + (fillPrice * fillQty)
                val newAvg = totalValue.divide(newPosition.abs(), 8, RoundingMode.HALF_UP)
                avgEntryPrice = newAvg
            }
            else -> avgEntryPrice = fillPrice
        }
    }
}
