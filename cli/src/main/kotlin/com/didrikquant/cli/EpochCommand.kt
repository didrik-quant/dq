package com.didrikquant.cli

import com.didrikquant.replay.storage.ChronicleEventStore
import com.didrikquant.replay.storage.RecordedEvent
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

public class EpochCommand(private val dataDir: Path) {

    public fun run(
        fromTimestamp: Long,
        toTimestamp: Long,
        compareFromTimestamp: Long? = null,
        compareToTimestamp: Long? = null,
    ) {
        val currentMetrics = computeMetrics(fromTimestamp, toTimestamp)

        println(MarkdownRenderer.render(currentMetrics))

        if (compareFromTimestamp != null && compareToTimestamp != null) {
            val previousMetrics = computeMetrics(compareFromTimestamp, compareToTimestamp)
            println()
            println(MarkdownRenderer.renderComparison(previousMetrics, currentMetrics))
        }
    }

    private fun computeMetrics(fromTimestamp: Long, toTimestamp: Long): EpochMetrics {
        val store = ChronicleEventStore(dataDir)
        val tailer = store.createTailer()

        val startDate = Instant.ofEpochMilli(fromTimestamp).atZone(ZoneOffset.UTC).toLocalDate()
        tailer.seekToDate(startDate)

        val accumulator = MetricsAccumulator(fromTimestamp, toTimestamp)
        var skippedEvents = 0

        try {
            while (tailer.hasNext()) {
                try {
                    val recorded = tailer.next()
                    if (recorded.eventTimestamp < fromTimestamp) continue
                    if (recorded.eventTimestamp > toTimestamp) break

                    val event = RecordedEvent.toEvent(recorded)
                    accumulator.process(event)
                } catch (e: Exception) {
                    skippedEvents++
                    if (skippedEvents > 100) {
                        System.err.println("Too many corrupted events, stopping")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error reading recordings: ${e.message}")
        }

        tailer.close()
        store.close()

        if (skippedEvents > 0) {
            System.err.println("Warning: Skipped $skippedEvents corrupted events")
        }

        return accumulator.build()
    }
}
