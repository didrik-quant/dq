package com.didrikquant.replay.metrics

import com.didrikquant.core.Event
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

public data class MetricsSnapshot(
    val bookSnapshotCount: Long,
    val bookUpdateCount: Long,
    val orderAcceptedCount: Int,
    val orderFillCount: Int,
    val orderCanceledCount: Int,
    val orderRejectedCount: Int,
    val connectCount: Int,
    val disconnectCount: Int,
    val maxPosition: BigDecimal,
    val minPosition: BigDecimal,
)

public class ReplayMetrics {

    private val bookSnapshotCount = AtomicLong(0)
    private val bookUpdateCount = AtomicLong(0)
    private val orderAcceptedCount = AtomicInteger(0)
    private val orderFillCount = AtomicInteger(0)
    private val orderCanceledCount = AtomicInteger(0)
    private val orderRejectedCount = AtomicInteger(0)
    private val connectCount = AtomicInteger(0)
    private val disconnectCount = AtomicInteger(0)
    private val maxPosition = AtomicReference(BigDecimal.ZERO)
    private val minPosition = AtomicReference(BigDecimal.ZERO)

    public fun record(event: Event) {
        when (event) {
            is Event.BookSnapshot -> bookSnapshotCount.incrementAndGet()
            is Event.BookUpdate -> bookUpdateCount.incrementAndGet()
            is Event.OrderAccepted -> orderAcceptedCount.incrementAndGet()
            is Event.OrderFill -> orderFillCount.incrementAndGet()
            is Event.OrderCanceled -> orderCanceledCount.incrementAndGet()
            is Event.OrderRejected -> orderRejectedCount.incrementAndGet()
            is Event.Connected -> connectCount.incrementAndGet()
            is Event.Disconnected -> disconnectCount.incrementAndGet()
            else -> {}
        }
    }

    public fun updatePosition(position: BigDecimal) {
        maxPosition.updateAndGet { current -> maxOf(current, position) }
        minPosition.updateAndGet { current -> minOf(current, position) }
    }

    public fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        bookSnapshotCount = bookSnapshotCount.get(),
        bookUpdateCount = bookUpdateCount.get(),
        orderAcceptedCount = orderAcceptedCount.get(),
        orderFillCount = orderFillCount.get(),
        orderCanceledCount = orderCanceledCount.get(),
        orderRejectedCount = orderRejectedCount.get(),
        connectCount = connectCount.get(),
        disconnectCount = disconnectCount.get(),
        maxPosition = maxPosition.get(),
        minPosition = minPosition.get(),
    )

    public fun reset() {
        bookSnapshotCount.set(0)
        bookUpdateCount.set(0)
        orderAcceptedCount.set(0)
        orderFillCount.set(0)
        orderCanceledCount.set(0)
        orderRejectedCount.set(0)
        connectCount.set(0)
        disconnectCount.set(0)
        maxPosition.set(BigDecimal.ZERO)
        minPosition.set(BigDecimal.ZERO)
    }
}
