package com.didrikquant.bot.handlers

import com.didrikquant.core.disruptor.MutableEvent
import com.lmax.disruptor.EventHandler

/**
 * Final handler in the pipeline that clears the event after all processing.
 *
 * This follows the LMAX Disruptor best practice of clearing object references
 * to prevent memory leaks, since events are reused in the ring buffer.
 *
 * MUST be the last handler in the chain (after EventRecorder if present).
 */
public class CleanupHandler : EventHandler<MutableEvent> {
    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        event.clear()
    }
}
