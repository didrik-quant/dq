package com.didrikquant.cli

import com.didrikquant.core.Event
import com.didrikquant.core.OrderBook
import com.didrikquant.replay.storage.ChronicleEventStore
import com.didrikquant.replay.storage.RecordedEvent
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

public class BookCommand(private val dataDir: Path) {

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    public fun run(targetTimestamp: Long) {
        val store = ChronicleEventStore(dataDir)
        val tailer = store.createTailer()

        val targetDate = Instant.ofEpochMilli(targetTimestamp).atZone(ZoneOffset.UTC).toLocalDate()

        tailer.seekToDate(targetDate)

        var orderBook: OrderBook? = null
        var lastEventTimestamp: Long = 0
        var sequence: Long = 0

        while (tailer.hasNext()) {
            val recorded = tailer.next()
            if (recorded.eventTimestamp > targetTimestamp) break

            val event = RecordedEvent.toEvent(recorded)
            when (event) {
                is Event.BookSnapshot -> {
                    if (orderBook == null || orderBook.symbol != event.symbol) {
                        orderBook = OrderBook(event.symbol)
                    }
                    orderBook.applySnapshot(event.bids, event.asks, sequence++)
                    lastEventTimestamp = event.timestamp
                }
                is Event.BookUpdate -> {
                    orderBook?.applyUpdate(event.bids, event.asks, sequence++)
                    lastEventTimestamp = event.timestamp
                }
                else -> {}
            }
        }

        tailer.close()
        store.close()

        if (orderBook == null || !orderBook.isValid()) {
            println("No order book data found at timestamp $targetTimestamp")
            return
        }

        val timeStr = timeFormatter.format(Instant.ofEpochMilli(lastEventTimestamp))
        println("Order Book at $timeStr (${orderBook.symbol})")
        println()
        println("Mid: ${orderBook.midPrice}")
        println("Spread: ${orderBook.spreadBps} bps")
        println()

        val bids = orderBook.topBids(10)
        val asks = orderBook.topAsks(10)

        println("%-20s  |  %-20s".format("BIDS", "ASKS"))
        println("%-12s  %-8s  |  %-12s  %-8s".format("Price", "Qty", "Price", "Qty"))
        println("-".repeat(50))

        val maxRows = maxOf(bids.size, asks.size)
        for (i in 0 until maxRows) {
            val bidStr = if (i < bids.size) {
                "%-12s  %-8s".format(bids[i].price.toPlainString(), bids[i].qty.toPlainString())
            } else {
                " ".repeat(22)
            }
            val askStr = if (i < asks.size) {
                "%-12s  %-8s".format(asks[i].price.toPlainString(), asks[i].qty.toPlainString())
            } else {
                ""
            }
            println("$bidStr  |  $askStr")
        }
    }
}
