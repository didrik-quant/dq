package com.didrikquant.execution

import com.didrikquant.core.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

public class OrderManager(private val symbol: String) {

    private val orders = ConcurrentHashMap<String, TrackedOrder>()
    private val clOrdIdToOrderId = ConcurrentHashMap<String, String>()
    private val position = AtomicReference(BigDecimal.ZERO)
    private val realizedPnl = AtomicReference(BigDecimal.ZERO)
    private val avgEntryPrice = AtomicReference<BigDecimal?>(null)

    public fun getPosition(): BigDecimal = position.get()

    public fun getRealizedPnl(): BigDecimal = realizedPnl.get()

    public fun getOpenOrderCount(): Int =
        orders.values.count { it.status == OrderStatus.OPEN || it.status == OrderStatus.PARTIALLY_FILLED }

    public fun getOpenOrders(): List<TrackedOrder> =
        orders.values.filter { it.status == OrderStatus.OPEN || it.status == OrderStatus.PARTIALLY_FILLED }

    public fun getOpenOrderBySide(side: Side): TrackedOrder? =
        orders.values.find {
            it.side == side && (it.status == OrderStatus.OPEN || it.status == OrderStatus.PARTIALLY_FILLED)
        }

    public fun generateClOrdId(): String = UUID.randomUUID().toString().replace("-", "").take(18)

    public fun registerPendingOrder(
        clOrdId: String,
        side: Side,
        price: BigDecimal,
        qty: BigDecimal,
    ) {
        val order = TrackedOrder(
            orderId = "",
            clOrdId = clOrdId,
            symbol = symbol,
            side = side,
            price = price,
            originalQty = qty,
            status = OrderStatus.PENDING,
        )
        orders[clOrdId] = order
    }

    public fun onOrderAccepted(event: Event.OrderAccepted) {
        val existing = orders.remove(event.clOrdId)
        val order = (existing ?: TrackedOrder(
            orderId = event.orderId,
            clOrdId = event.clOrdId,
            symbol = event.symbol,
            side = event.side,
            price = event.price,
            originalQty = event.qty,
        )).copy(orderId = event.orderId, status = OrderStatus.OPEN)

        orders[event.orderId] = order
        clOrdIdToOrderId[event.clOrdId] = event.orderId
    }

    public fun onOrderFill(event: Event.OrderFill) {
        val order = orders[event.orderId] ?: return

        val newFilledQty = event.cumQty
        val remaining = order.originalQty - newFilledQty
        val newStatus = if (remaining <= BigDecimal.ZERO) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED

        orders[event.orderId] = order.copy(filledQty = newFilledQty, status = newStatus)

        val currentPosition = position.get()
        val fillPrice = event.fillPrice
        val fillQty = event.fillQty

        val avgEntry = avgEntryPrice.get()
        if (avgEntry != null) {
            val isReducing = when (event.side) {
                Side.BUY -> currentPosition < BigDecimal.ZERO
                Side.SELL -> currentPosition > BigDecimal.ZERO
            }
            if (isReducing) {
                val pnlPerUnit = when (event.side) {
                    Side.BUY -> avgEntry - fillPrice
                    Side.SELL -> fillPrice - avgEntry
                }
                val thisPnl = pnlPerUnit * fillQty
                realizedPnl.updateAndGet { it + thisPnl }
            }
        }

        val positionDelta = when (event.side) {
            Side.BUY -> fillQty
            Side.SELL -> fillQty.negate()
        }
        val newPosition = position.updateAndGet { it + positionDelta }

        updateAvgEntryPrice(currentPosition, newPosition, fillPrice, fillQty, event.side)

        if (newStatus == OrderStatus.FILLED) {
            orders.remove(event.orderId)
        }
    }

    private fun updateAvgEntryPrice(
        oldPosition: BigDecimal,
        newPosition: BigDecimal,
        fillPrice: BigDecimal,
        fillQty: BigDecimal,
        side: Side,
    ) {
        if (newPosition.signum() == 0) {
            avgEntryPrice.set(null)
            return
        }

        val currentAvg = avgEntryPrice.get()
        if (currentAvg == null) {
            avgEntryPrice.set(fillPrice)
            return
        }

        val wasFlat = oldPosition.signum() == 0
        val sameDirection = oldPosition.signum() == newPosition.signum()

        when {
            wasFlat -> avgEntryPrice.set(fillPrice)
            sameDirection -> {
                val totalValue = (currentAvg * oldPosition.abs()) + (fillPrice * fillQty)
                val newAvg = totalValue.divide(newPosition.abs(), 8, RoundingMode.HALF_UP)
                avgEntryPrice.set(newAvg)
            }
            else -> avgEntryPrice.set(fillPrice)
        }
    }

    public fun onOrderCanceled(event: Event.OrderCanceled) {
        orders.remove(event.orderId)
        orders.remove(event.clOrdId)
    }

    public fun getOrderIdByClOrdId(clOrdId: String): String? = clOrdIdToOrderId[clOrdId]
}
