package com.didrikquant.bot.handlers

import com.didrikquant.core.BotFatalException
import com.didrikquant.core.Event
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

/**
 * Centralized handler for all execution state management.
 *
 * Processes order events FIRST to update OrderManager state, then takes an immutable snapshot
 * that downstream handlers can use. This ensures the snapshot always reflects the latest state.
 *
 * Order of operations:
 * 1. Process order events (accept, fill, cancel, amend, reject)
 * 2. Check risk limits (max loss)
 * 3. Take snapshot for downstream handlers
 */
public class ExecutionStateHandler(
    private val orderManager: OrderManager,
    private val maxLossUsd: BigDecimal,
) : EventHandler<MutableEvent> {

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        // 1. Process order events to update state FIRST
        when (val e = event.event) {
            is Event.OrderAccepted -> {
                orderManager.onOrderAccepted(e)
                logger.info { "Order accepted: ${e.orderId} (${e.clOrdId})" }
            }
            is Event.OrderFill -> {
                orderManager.onOrderFill(e)
                val pnl = orderManager.getRealizedPnl()
                val position = orderManager.getPosition()

                // 2. Check max loss after processing fill
                if (pnl < maxLossUsd.negate()) {
                    throw BotFatalException("Max loss exceeded: PnL=$pnl, limit=-$maxLossUsd")
                }

                logger.info { "Fill: ${e.fillQty} @ ${e.fillPrice}, position=$position, pnl=$pnl" }
            }
            is Event.OrderCanceled -> {
                orderManager.onOrderCanceled(e)
                logger.info { "Order canceled: ${e.orderId} - ${e.reason}" }
            }
            is Event.OrderAmended -> {
                orderManager.onOrderAmended(e)
                logger.info { "Order amended: ${e.oldOrderId} -> ${e.newOrderId} @ ${e.newPrice}" }
            }
            is Event.OrderRejected -> {
                throw BotFatalException("Order rejected: ${e.clOrdId} - ${e.reason}")
            }
            else -> {}
        }

        // 3. Take snapshot AFTER processing events - downstream handlers see fresh state
        event.executionSnapshot = orderManager.snapshot()
    }
}
