package com.didrikquant.bot.handlers

import com.didrikquant.core.BotFatalException
import com.didrikquant.core.Event
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.strategy.Strategy
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

public class StrategyHandler(
    private val strategy: Strategy,
    private val requoteIntervalMs: Long,
) : EventHandler<MutableEvent> {
    private val lastQuoteTime = AtomicLong(0)

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        val bookSnapshot = event.orderBookSnapshot ?: return
        val execSnapshot = event.executionSnapshot ?: return

        when (event.event) {
            is Event.BookSnapshot, is Event.BookUpdate -> {
                val now = System.currentTimeMillis()
                if (now - lastQuoteTime.get() < requoteIntervalMs) {
                    return
                }

                if (!bookSnapshot.isValid()) {
                    return
                }

                val actions = try {
                    strategy.onBookSnapshot(bookSnapshot, execSnapshot.position, execSnapshot.openOrders)
                } catch (e: Exception) {
                    throw BotFatalException("Strategy exception: ${e.message}", e)
                }

                if (actions.isNotEmpty()) {
                    event.actions = actions
                    lastQuoteTime.set(now)
                    logger.debug { "Strategy generated ${actions.size} actions at ${bookSnapshot.midPrice}" }
                }
            }
            else -> {}
        }
    }
}
