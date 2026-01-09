package com.didrikquant.bot.handlers

import com.didrikquant.core.disruptor.MutableEvent
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

public class MonitoringHandler(
    private val logEveryNEvents: Int = 1000,
) : EventHandler<MutableEvent> {
    private val eventCount = AtomicLong(0)

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        val count = eventCount.incrementAndGet()
        if (count % logEveryNEvents != 0L) return

        val book = event.orderBookSnapshot
        val pos = event.positionSnapshot

        logger.info {
            "Status[$count]: mid=${book?.midPrice}, spread=${book?.spreadBps}bps, " +
                "position=${pos?.position}, pnl=${pos?.realizedPnl}, " +
                "openOrders=${event.openOrders.size}"
        }
    }
}
