package com.didrikquant.core.disruptor

import com.lmax.disruptor.BusySpinWaitStrategy
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.RingBuffer
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

public object DisruptorConfig {
    public const val DEFAULT_BUFFER_SIZE: Int = 1024

    public fun createDisruptor(
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        handlers: Array<EventHandler<MutableEvent>>,
    ): Disruptor<MutableEvent> {
        val factory = EventFactory { MutableEvent() }
        val threadFactory = NamedThreadFactory("disruptor")

        val disruptor = Disruptor(
            factory,
            bufferSize,
            threadFactory,
            ProducerType.MULTI,
            BusySpinWaitStrategy(),
        )

        @Suppress("SpreadOperator")
        disruptor.handleEventsWith(*handlers)

        return disruptor
    }

    public fun <T> publish(ringBuffer: RingBuffer<MutableEvent>, eventSetter: (MutableEvent) -> Unit) {
        val sequence = ringBuffer.next()
        try {
            val event = ringBuffer[sequence]
            event.clear()
            eventSetter(event)
        } finally {
            ringBuffer.publish(sequence)
        }
    }
}

private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val counter = AtomicInteger(0)

    override fun newThread(r: Runnable): Thread =
        Thread(r, "$prefix-${counter.incrementAndGet()}").apply {
            isDaemon = true
        }
}
