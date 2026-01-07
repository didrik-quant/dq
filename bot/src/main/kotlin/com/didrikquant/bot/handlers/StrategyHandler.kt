package com.didrikquant.bot.handlers

import com.didrikquant.core.Event
import com.didrikquant.core.OrderBook
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.didrikquant.risk.KillSwitch
import com.didrikquant.strategy.OrderIntent
import com.didrikquant.strategy.Strategy
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

public class StrategyHandler(
    private val strategy: Strategy,
    private val orderBook: OrderBook,
    private val orderManager: OrderManager,
    private val killSwitch: KillSwitch,
    private val requoteIntervalMs: Long,
) : EventHandler<MutableEvent> {
    private val lastQuoteTime = AtomicLong(0)
    private val pendingIntents = AtomicReference<List<OrderIntent>>(emptyList())

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        when (event.event) {
            is Event.BookSnapshot, is Event.BookUpdate -> {
                if (killSwitch.isTriggered()) {
                    logger.warn { "Kill switch triggered: ${killSwitch.getTriggerReason()}" }
                    return
                }

                val now = System.currentTimeMillis()
                if (now - lastQuoteTime.get() < requoteIntervalMs) {
                    return
                }

                if (!orderBook.isValid()) {
                    return
                }

                val position = orderManager.getPosition()
                val intents = strategy.onOrderBook(orderBook, position)

                if (intents.isNotEmpty()) {
                    pendingIntents.set(intents)
                    lastQuoteTime.set(now)
                    logger.debug { "Strategy generated ${intents.size} intents at ${orderBook.midPrice}" }
                }
            }
            else -> {}
        }
    }

    public fun consumeIntents(): List<OrderIntent> = pendingIntents.getAndSet(emptyList())
}
