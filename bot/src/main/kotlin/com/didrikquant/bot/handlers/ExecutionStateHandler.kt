package com.didrikquant.bot.handlers

import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.lmax.disruptor.EventHandler

public class ExecutionStateHandler(
    private val orderManager: OrderManager,
) : EventHandler<MutableEvent> {

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        event.executionSnapshot = orderManager.snapshot()
    }
}
