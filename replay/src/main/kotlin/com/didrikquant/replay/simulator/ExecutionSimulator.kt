package com.didrikquant.replay.simulator

import com.didrikquant.core.Command
import com.didrikquant.core.Event
import com.didrikquant.core.OrderBook
import com.didrikquant.core.disruptor.MutableEvent
import com.lmax.disruptor.RingBuffer
import mu.KotlinLogging
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

public class ExecutionSimulator(
    private val ringBuffer: RingBuffer<MutableEvent>,
    private val fillModel: SimpleFillModel = SimpleFillModel(),
    private val feeRate: BigDecimal = BigDecimal("0.0002"),
) {
    private val pendingOrders = ConcurrentHashMap<String, SimulatedOrder>()
    private val orderIdCounter = AtomicLong(0)

    public fun processCommand(command: Command, currentTimestamp: Long) {
        when (command) {
            is Command.PlaceOrder -> {
                val orderId = "SIM-${orderIdCounter.incrementAndGet()}"
                val order = SimulatedOrder(
                    orderId = orderId,
                    clOrdId = command.clOrdId,
                    symbol = command.symbol,
                    side = command.side,
                    price = command.price,
                    qty = command.qty,
                    createdAt = currentTimestamp,
                )
                pendingOrders[command.clOrdId] = order

                publishEvent(
                    Event.OrderAccepted(
                        orderId = orderId,
                        clOrdId = command.clOrdId,
                        symbol = command.symbol,
                        side = command.side,
                        price = command.price,
                        qty = command.qty,
                        timestamp = currentTimestamp,
                    ),
                )

                logger.debug { "Simulated order placed: $orderId ${command.side} ${command.qty} @ ${command.price}" }
            }

            is Command.CancelOrder -> {
                val order = pendingOrders.remove(command.clOrdId)
                if (order != null) {
                    publishEvent(
                        Event.OrderCanceled(
                            orderId = order.orderId,
                            clOrdId = order.clOrdId,
                            reason = "User requested",
                            timestamp = currentTimestamp,
                        ),
                    )
                    logger.debug { "Simulated order canceled: ${order.clOrdId}" }
                }
            }

            is Command.CancelAll -> {
                val ordersToCancel = pendingOrders.values.filter { order ->
                    command.symbol == null || order.symbol == command.symbol
                }
                ordersToCancel.forEach { order ->
                    pendingOrders.remove(order.clOrdId)
                    publishEvent(
                        Event.OrderCanceled(
                            orderId = order.orderId,
                            clOrdId = order.clOrdId,
                            reason = "Cancel all",
                            timestamp = currentTimestamp,
                        ),
                    )
                }
                if (ordersToCancel.isNotEmpty()) {
                    logger.debug { "Simulated cancel all: ${ordersToCancel.size} orders" }
                }
            }

            is Command.AmendOrder -> {
                val order = pendingOrders[command.clOrdId]
                if (order != null) {
                    val amendedOrder = order.copy(price = command.newPrice)
                    pendingOrders[command.clOrdId] = amendedOrder
                    publishEvent(
                        Event.OrderAmended(
                            oldOrderId = order.orderId,
                            newOrderId = order.orderId,
                            clOrdId = order.clOrdId,
                            newPrice = command.newPrice,
                            newQty = command.newQty,
                            timestamp = currentTimestamp,
                        ),
                    )
                    logger.debug { "Simulated order amended: ${order.clOrdId} -> ${command.newPrice}" }
                }
            }
        }
    }

    public fun onBookUpdate(book: OrderBook, currentTimestamp: Long) {
        val fills = fillModel.checkFills(pendingOrders.values, book, currentTimestamp)

        for (fill in fills) {
            pendingOrders.remove(fill.order.clOrdId)

            publishEvent(
                Event.OrderFill(
                    orderId = fill.order.orderId,
                    clOrdId = fill.order.clOrdId,
                    execId = "SIM-EXEC-${System.nanoTime()}",
                    symbol = fill.order.symbol,
                    side = fill.order.side,
                    fillQty = fill.fillQty,
                    fillPrice = fill.fillPrice,
                    cumQty = fill.fillQty,
                    leavesQty = BigDecimal.ZERO,
                    timestamp = fill.timestamp,
                ),
            )

            logger.debug {
                "Simulated fill: ${fill.order.side} ${fill.fillQty} @ ${fill.fillPrice}"
            }
        }
    }

    public fun getPendingOrderCount(): Int = pendingOrders.size

    public fun reset() {
        pendingOrders.clear()
        orderIdCounter.set(0)
    }

    private fun publishEvent(event: Event) {
        val sequence = ringBuffer.next()
        try {
            val mutableEvent = ringBuffer[sequence]
            mutableEvent.clear()
            mutableEvent.event = event
        } finally {
            ringBuffer.publish(sequence)
        }
    }
}
