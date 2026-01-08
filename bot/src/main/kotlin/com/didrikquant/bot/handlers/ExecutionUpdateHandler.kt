package com.didrikquant.bot.handlers

import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.lmax.disruptor.EventHandler

/**
 * Registers pending orders created by RiskHandler.
 *
 * This handler runs AFTER OutputHandler sends commands to the exchange.
 * It registers the pending orders in OrderManager so they're visible in the next snapshot.
 *
 * Note: Order event processing (fills, cancels, accepts) is handled by ExecutionStateHandler,
 * which runs BEFORE this handler to ensure snapshots are always fresh.
 */
public class ExecutionUpdateHandler(
    private val orderManager: OrderManager,
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
    }
}
