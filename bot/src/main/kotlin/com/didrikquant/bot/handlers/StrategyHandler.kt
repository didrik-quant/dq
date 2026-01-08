package com.didrikquant.bot.handlers

import com.didrikquant.core.Event
import com.didrikquant.core.OrderBook
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.didrikquant.risk.KillSwitch
import com.didrikquant.strategy.Strategy
import com.didrikquant.strategy.StrategyAction
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
    private val pendingActions = AtomicReference<List<StrategyAction>>(emptyList())

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
                val openOrders = orderManager.getOpenOrders()
                val actions = try {
                    strategy.onOrderBook(orderBook, position, openOrders)
                } catch (e: Exception) {
                    logger.error(e) { "Strategy error, triggering kill switch" }
                    killSwitch.manualTrigger("Strategy exception: ${e.message}")
                    return
                }

                if (actions.isNotEmpty()) {
                    pendingActions.set(actions)
                    lastQuoteTime.set(now)
                    logger.debug { "Strategy generated ${actions.size} actions at ${orderBook.midPrice}" }
                }
            }
            else -> {}
        }
    }

    public fun consumeActions(): List<StrategyAction> = pendingActions.getAndSet(emptyList())
}
