package com.didrikquant.bot.handlers

import com.didrikquant.core.Event
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.didrikquant.risk.KillSwitch
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

public class ExecutionHandler(
    private val orderManager: OrderManager,
    private val killSwitch: KillSwitch,
) : EventHandler<MutableEvent> {
    private val _fillCount = AtomicInteger(0)
    public val fillCount: Int get() = _fillCount.get()

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        when (val e = event.event) {
            is Event.OrderAccepted -> {
                orderManager.onOrderAccepted(e)
                logger.info { "Order accepted: ${e.orderId} (${e.clOrdId})" }
            }
            is Event.OrderFill -> {
                orderManager.onOrderFill(e)
                val count = _fillCount.incrementAndGet()
                val pnl = orderManager.getRealizedPnl()
                killSwitch.updatePnl(pnl)
                logger.info {
                    "Fill #$count: ${e.fillQty} @ ${e.fillPrice}, " +
                        "position=${orderManager.getPosition()}, pnl=$pnl"
                }
            }
            is Event.OrderCanceled -> {
                orderManager.onOrderCanceled(e)
                logger.info { "Order canceled: ${e.orderId} - ${e.reason}" }
            }
            is Event.OrderRejected -> {
                logger.error { "Order rejected: ${e.clOrdId} - ${e.reason}" }
            }
            else -> {}
        }
    }
}
