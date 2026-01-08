package com.didrikquant.bot.handlers

import com.didrikquant.core.Event
import com.didrikquant.core.OrderBook
import com.didrikquant.core.disruptor.MutableEvent
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

public class BookHandler(
    private val orderBook: OrderBook,
) : EventHandler<MutableEvent> {
    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        when (val e = event.event) {
            is Event.BookSnapshot -> {
                orderBook.applySnapshot(e.bids, e.asks, sequence)
                event.orderBookSnapshot = orderBook.snapshot()
                logger.debug { "Book snapshot: ${event.orderBookSnapshot}" }
            }
            is Event.BookUpdate -> {
                orderBook.applyUpdate(e.bids, e.asks, sequence)
                event.orderBookSnapshot = orderBook.snapshot()
                logger.trace { "Book update: ${event.orderBookSnapshot}" }
            }
            else -> {}
        }
    }
}
