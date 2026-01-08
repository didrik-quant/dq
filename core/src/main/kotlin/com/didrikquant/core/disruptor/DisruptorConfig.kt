package com.didrikquant.core.disruptor

import com.lmax.disruptor.BusySpinWaitStrategy
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.ExceptionHandler
import com.lmax.disruptor.RingBuffer
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import mu.KotlinLogging
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

public object DisruptorConfig {
    public const val DEFAULT_BUFFER_SIZE: Int = 1024

    /**
     * Creates a Disruptor with the given handlers chained sequentially.
     *
     * @param handlers Array of handlers to chain in order
     * @param onFatalError Called on any exception, should cancel orders and prepare for exit
     */
    public fun createDisruptor(
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        handlers: Array<EventHandler<MutableEvent>>,
        onFatalError: ((Throwable) -> Unit)? = null,
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

        disruptor.setDefaultExceptionHandler(object : ExceptionHandler<MutableEvent> {
            override fun handleEventException(ex: Throwable, sequence: Long, event: MutableEvent) {
                logger.error(ex) { "Fatal error at sequence $sequence - shutting down" }
                onFatalError?.invoke(ex)
                exitProcess(1)
            }

            override fun handleOnStartException(ex: Throwable) {
                logger.error(ex) { "Disruptor start exception - shutting down" }
                onFatalError?.invoke(ex)
                exitProcess(1)
            }

            override fun handleOnShutdownException(ex: Throwable) {
                logger.error(ex) { "Disruptor shutdown exception" }
            }
        })

        if (handlers.isNotEmpty()) {
            var group = disruptor.handleEventsWith(handlers[0])
            for (i in 1 until handlers.size) {
                group = group.then(handlers[i])
            }
        }

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
            isDaemon = false
        }
}
