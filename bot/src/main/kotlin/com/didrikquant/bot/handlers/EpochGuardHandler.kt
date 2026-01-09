package com.didrikquant.bot.handlers

import com.didrikquant.core.BotFatalException
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.orderstate.OrderStateEvent
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

public class EpochGuardHandler(
    private val targetTradeCount: Int?,
    private val maxDurationMs: Long?,
) : EventHandler<MutableEvent> {
    private val startTimeMs = System.currentTimeMillis()
    private val fillCount = AtomicInteger(0)

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        // Count fills from OrderStateEvent.ExecutionReport.Trade
        if (event.orderEvent is OrderStateEvent.ExecutionReport.Trade) {
            val count = fillCount.incrementAndGet()
            logger.debug { "Fill count: $count / ${targetTradeCount ?: "unlimited"}" }

            if (targetTradeCount != null && count >= targetTradeCount) {
                throw BotFatalException("Epoch complete: reached $count trades")
            }
        }

        // Check duration
        if (maxDurationMs != null) {
            val elapsed = System.currentTimeMillis() - startTimeMs
            if (elapsed >= maxDurationMs) {
                throw BotFatalException("Epoch complete: max duration ${elapsed}ms reached")
            }
        }
    }

    public fun getFillCount(): Int = fillCount.get()
}
