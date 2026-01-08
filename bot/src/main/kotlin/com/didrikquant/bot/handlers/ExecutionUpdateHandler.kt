package com.didrikquant.bot.handlers

import com.didrikquant.core.BotFatalException
import com.didrikquant.core.Event
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

public class ExecutionUpdateHandler(
    private val orderManager: OrderManager,
    private val maxLossUsd: BigDecimal,
) : EventHandler<MutableEvent> {

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        // Register pending orders from RiskHandler
        event.newPendingOrders.forEach { intent ->
            orderManager.registerPendingOrder(intent.clOrdId, intent.side, intent.price, intent.qty)
        }

        // Process order events
        when (val e = event.event) {
            is Event.OrderAccepted -> {
                orderManager.onOrderAccepted(e)
                logger.info { "Order accepted: ${e.orderId} (${e.clOrdId})" }
            }
            is Event.OrderFill -> {
                orderManager.onOrderFill(e)
                val pnl = orderManager.getRealizedPnl()
                val position = orderManager.getPosition()

                if (pnl < maxLossUsd.negate()) {
                    throw BotFatalException("Max loss exceeded: PnL=$pnl, limit=-$maxLossUsd")
                }

                logger.info { "Fill: ${e.fillQty} @ ${e.fillPrice}, position=$position, pnl=$pnl" }
            }
            is Event.OrderCanceled -> {
                orderManager.onOrderCanceled(e)
                logger.info { "Order canceled: ${e.orderId} - ${e.reason}" }
            }
            is Event.OrderRejected -> {
                throw BotFatalException("Order rejected: ${e.clOrdId} - ${e.reason}")
            }
            else -> {}
        }
    }
}
