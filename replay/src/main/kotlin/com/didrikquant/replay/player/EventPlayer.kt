package com.didrikquant.replay.player

import com.didrikquant.core.Event
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.replay.storage.ChronicleEventStore
import com.didrikquant.replay.storage.RecordedEvent
import com.lmax.disruptor.RingBuffer
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

public data class ReplayStats(
    val eventCount: Long,
    val elapsedMs: Long,
    val firstEventTimestamp: Long,
    val lastEventTimestamp: Long,
) {
    public val eventsPerSecond: Long
        get() = if (elapsedMs > 0) eventCount * 1000 / elapsedMs else 0
}

public class EventPlayer(
    private val store: ChronicleEventStore,
    private val ringBuffer: RingBuffer<MutableEvent>,
    private val config: ReplayConfig,
) {
    public val clock: ReplayClock = ReplayClock()

    public fun replay(): ReplayStats {
        var eventCount = 0L
        var firstTimestamp = 0L
        var lastTimestamp = 0L
        val startTime = System.nanoTime()

        val tailer = store.createTailer()
        tailer.seekToDate(config.startDate)

        while (tailer.hasNext()) {
            val recorded = tailer.next()

            if (recorded.eventTimestamp > config.endMillis) break
            if (recorded.eventTimestamp < config.startMillis) continue

            val event = RecordedEvent.toEvent(recorded)

            if (!matchesFilter(event)) continue

            clock.advanceTo(recorded.eventTimestamp)

            publishEvent(event)

            if (firstTimestamp == 0L) firstTimestamp = recorded.eventTimestamp
            lastTimestamp = recorded.eventTimestamp
            eventCount++

            if (eventCount % 100_000 == 0L) {
                logger.debug { "Replayed $eventCount events..." }
            }
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        logger.info {
            "Replay complete: $eventCount events in ${elapsedMs}ms " +
                "(${if (elapsedMs > 0) eventCount * 1000 / elapsedMs else 0} events/sec)"
        }

        return ReplayStats(
            eventCount = eventCount,
            elapsedMs = elapsedMs,
            firstEventTimestamp = firstTimestamp,
            lastEventTimestamp = lastTimestamp,
        )
    }

    private fun matchesFilter(event: Event): Boolean {
        val symbol = when (event) {
            is Event.BookSnapshot -> event.symbol
            is Event.BookUpdate -> event.symbol
            is Event.OrderAccepted -> event.symbol
            is Event.OrderFill -> event.symbol
            else -> return true
        }
        return config.matchesSymbol(symbol)
    }

    private fun publishEvent(event: Event) {
        val sequence = ringBuffer.next()
        try {
            val mutableEvent = ringBuffer[sequence]
            mutableEvent.clear()
            mutableEvent.event = event
        } finally {
            ringBuffer.publish(sequence)
        }
    }
}
