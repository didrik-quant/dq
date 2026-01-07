package com.didrikquant.bot.handlers

import com.didrikquant.core.Event
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

public class ExecutionHandler(
    private val orderManager: OrderManager,
) : EventHandler<MutableEvent> {
    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        when (val e = event.event) {
            is Event.OrderAccepted -> {
                orderManager.onOrderAccepted(e)
                logger.info { "Order accepted: ${e.orderId} (${e.clOrdId})" }
            }
            is Event.OrderFill -> {
                orderManager.onOrderFill(e)
                logger.info {
                    "Fill: ${e.fillQty} @ ${e.fillPrice}, position=${orderManager.getPosition()}"
                }
            }
            is Event.OrderCanceled -> {
                orderManager.onOrderCanceled(e)
                logger.info { "Order canceled: ${e.orderId} - ${e.reason}" }
            }
            is Event.OrderRejected -> {
                logger.error { "Order rejected: ${e.clOrdId} - ${e.reason}" }
            }
            else -> {}
        }
    }
}
