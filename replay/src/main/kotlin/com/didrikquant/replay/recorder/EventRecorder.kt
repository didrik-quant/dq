package com.didrikquant.replay.recorder

import com.didrikquant.core.Event
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.replay.storage.ChronicleEventStore
import com.didrikquant.replay.storage.RecordedEvent
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.io.Closeable
import java.nio.file.Files

private val logger = KotlinLogging.logger {}

public class EventRecorder(
    private val config: RecorderConfig,
) : EventHandler<MutableEvent>, Closeable {

    private val store: ChronicleEventStore

    init {
        Files.createDirectories(config.dataDir)
        store = ChronicleEventStore(config.dataDir, config.retentionDays)
        logger.info { "Event recording enabled: ${config.dataDir}" }
    }

    override fun onEvent(event: MutableEvent, sequence: Long, endOfBatch: Boolean) {
        val evt = event.event ?: return

        if (evt is Event.Heartbeat && !config.recordHeartbeats) return

        val recorded = RecordedEvent.fromEvent(evt, sequence, config.compressionLevel)
        store.append(recorded)
    }

    public fun cleanup() {
        store.cleanup()
    }

    override fun close() {
        store.close()
        logger.info { "Event recorder closed" }
    }
}
