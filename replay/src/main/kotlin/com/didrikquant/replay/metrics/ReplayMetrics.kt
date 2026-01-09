package com.didrikquant.replay.metrics

import com.didrikquant.core.Event
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

public data class MetricsSnapshot(
    val bookSnapshotCount: Long,
    val bookUpdateCount: Long,
    val connectCount: Long,
    val disconnectCount: Long,
    val maxPosition: BigDecimal,
    val minPosition: BigDecimal,
)

public class ReplayMetrics {

    private val bookSnapshotCount = AtomicLong(0)
    private val bookUpdateCount = AtomicLong(0)
    private val connectCount = AtomicLong(0)
    private val disconnectCount = AtomicLong(0)
    private val maxPosition = AtomicReference(BigDecimal.ZERO)
    private val minPosition = AtomicReference(BigDecimal.ZERO)

    public fun record(event: Event) {
        when (event) {
            is Event.BookSnapshot -> bookSnapshotCount.incrementAndGet()
            is Event.BookUpdate -> bookUpdateCount.incrementAndGet()
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
        connectCount = connectCount.get(),
        disconnectCount = disconnectCount.get(),
        maxPosition = maxPosition.get(),
        minPosition = minPosition.get(),
    )

    public fun reset() {
        bookSnapshotCount.set(0)
        bookUpdateCount.set(0)
        connectCount.set(0)
        disconnectCount.set(0)
        maxPosition.set(BigDecimal.ZERO)
        minPosition.set(BigDecimal.ZERO)
    }
}
