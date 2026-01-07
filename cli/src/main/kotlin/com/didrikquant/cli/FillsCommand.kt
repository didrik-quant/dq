package com.didrikquant.cli

import com.didrikquant.core.Event
import com.didrikquant.replay.storage.ChronicleEventStore
import com.didrikquant.replay.storage.RecordedEvent
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

public class FillsCommand(private val dataDir: Path) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    public fun run() {
        val store = ChronicleEventStore(dataDir)
        val tailer = store.createTailer()

        val lastEpochStart = Instant.now().minusMillis(3_600_000L)
        val startDate = lastEpochStart.atZone(ZoneOffset.UTC).toLocalDate()

        tailer.seekToDate(startDate)

        val fills = mutableListOf<Event.OrderFill>()

        while (tailer.hasNext()) {
            val recorded = tailer.next()
            if (recorded.eventTimestamp < lastEpochStart.toEpochMilli()) continue

            val event = RecordedEvent.toEvent(recorded)
            if (event is Event.OrderFill) {
                fills.add(event)
            }
        }

        tailer.close()
        store.close()

        if (fills.isEmpty()) {
            println("No fills in last epoch")
            return
        }

        println("Fills from last epoch (${fills.size} total):")
        println()
        println("%-12s  %-6s  %-4s  %-12s  %-12s".format("Time", "Symbol", "Side", "Price", "Qty"))
        println("-".repeat(60))

        for (fill in fills) {
            val time = timeFormatter.format(Instant.ofEpochMilli(fill.timestamp))
            println(
                "%-12s  %-6s  %-4s  %-12s  %-12s".format(
                    time,
                    fill.symbol.takeLast(6),
                    fill.side,
                    fill.fillPrice.toPlainString(),
                    fill.fillQty.toPlainString(),
                ),
            )
        }
    }
}
