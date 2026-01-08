package com.didrikquant.replay.storage

import com.didrikquant.core.ConnectionType
import com.didrikquant.core.Event
import com.didrikquant.core.PriceLevel
import com.didrikquant.core.Side
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal

@Serializable
public data class RecordedEvent(
    val wallTimestamp: Long,
    val eventTimestamp: Long,
    val sequence: Long,
    val eventType: String,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedEvent) return false
        return wallTimestamp == other.wallTimestamp &&
            eventTimestamp == other.eventTimestamp &&
            sequence == other.sequence &&
            eventType == other.eventType &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = wallTimestamp.hashCode()
        result = 31 * result + eventTimestamp.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + eventType.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        public fun fromEvent(event: Event, sequence: Long, compressionLevel: Int = 3): RecordedEvent {
            val eventTimestamp = event.extractTimestamp()
            val serialized = serializeEvent(event)
            val compressed = Compression.compress(serialized.toByteArray(Charsets.UTF_8), compressionLevel)

            return RecordedEvent(
                wallTimestamp = System.currentTimeMillis(),
                eventTimestamp = eventTimestamp,
                sequence = sequence,
                eventType = event::class.simpleName ?: "Unknown",
                payload = compressed,
            )
        }

        public fun toEvent(recorded: RecordedEvent): Event {
            val jsonString = Compression.decompressString(recorded.payload)
            return deserializeEvent(recorded.eventType, jsonString)
        }

        private fun serializeEvent(event: Event): String {
            val dto = EventDto.fromEvent(event)
            return json.encodeToString(dto)
        }

        private fun deserializeEvent(eventType: String, jsonString: String): Event {
            val dto = json.decodeFromString<EventDto>(jsonString)
            return dto.toEvent()
        }
    }
}

public fun Event.extractTimestamp(): Long = when (this) {
    is Event.BookSnapshot -> timestamp
    is Event.BookUpdate -> timestamp
    is Event.OrderAccepted -> timestamp
    is Event.OrderFill -> timestamp
    is Event.OrderCanceled -> timestamp
    is Event.OrderAmended -> timestamp
    is Event.OrderRejected -> timestamp
    is Event.Connected -> timestamp
    is Event.Disconnected -> timestamp
    is Event.Heartbeat -> timestamp
    is Event.Shutdown -> System.currentTimeMillis()
}

@Serializable
private data class EventDto(
    val type: String,
    val symbol: String? = null,
    val bids: List<PriceLevelDto>? = null,
    val asks: List<PriceLevelDto>? = null,
    val checksum: Long? = null,
    val timestamp: Long? = null,
    val orderId: String? = null,
    val clOrdId: String? = null,
    val execId: String? = null,
    val side: String? = null,
    val price: String? = null,
    val qty: String? = null,
    val fillQty: String? = null,
    val fillPrice: String? = null,
    val cumQty: String? = null,
    val leavesQty: String? = null,
    val reason: String? = null,
    val connectionType: String? = null,
    val oldOrderId: String? = null,
    val newOrderId: String? = null,
    val newPrice: String? = null,
    val newQty: String? = null,
) {
    fun toEvent(): Event = when (type) {
        "BookSnapshot" -> Event.BookSnapshot(
            symbol = symbol!!,
            bids = bids!!.map { it.toPriceLevel() },
            asks = asks!!.map { it.toPriceLevel() },
            checksum = checksum!!,
            timestamp = timestamp!!,
        )
        "BookUpdate" -> Event.BookUpdate(
            symbol = symbol!!,
            bids = bids!!.map { it.toPriceLevel() },
            asks = asks!!.map { it.toPriceLevel() },
            checksum = checksum!!,
            timestamp = timestamp!!,
        )
        "OrderAccepted" -> Event.OrderAccepted(
            orderId = orderId!!,
            clOrdId = clOrdId!!,
            symbol = symbol!!,
            side = Side.valueOf(side!!),
            price = BigDecimal(price!!),
            qty = BigDecimal(qty!!),
            timestamp = timestamp!!,
        )
        "OrderFill" -> Event.OrderFill(
            orderId = orderId!!,
            clOrdId = clOrdId!!,
            execId = execId!!,
            symbol = symbol!!,
            side = Side.valueOf(side!!),
            fillQty = BigDecimal(fillQty!!),
            fillPrice = BigDecimal(fillPrice!!),
            cumQty = BigDecimal(cumQty!!),
            leavesQty = BigDecimal(leavesQty!!),
            timestamp = timestamp!!,
        )
        "OrderCanceled" -> Event.OrderCanceled(
            orderId = orderId!!,
            clOrdId = clOrdId!!,
            reason = reason!!,
            timestamp = timestamp!!,
        )
        "OrderAmended" -> Event.OrderAmended(
            oldOrderId = oldOrderId!!,
            newOrderId = newOrderId!!,
            clOrdId = clOrdId ?: "",
            newPrice = BigDecimal(newPrice!!),
            newQty = newQty?.let { BigDecimal(it) },
            timestamp = timestamp!!,
        )
        "OrderRejected" -> Event.OrderRejected(
            clOrdId = clOrdId!!,
            reason = reason!!,
            timestamp = timestamp!!,
        )
        "Connected" -> Event.Connected(
            connectionType = ConnectionType.valueOf(connectionType!!),
            timestamp = timestamp!!,
        )
        "Disconnected" -> Event.Disconnected(
            connectionType = ConnectionType.valueOf(connectionType!!),
            reason = reason!!,
            timestamp = timestamp!!,
        )
        "Heartbeat" -> Event.Heartbeat(timestamp = timestamp!!)
        "Shutdown" -> Event.Shutdown
        else -> throw IllegalArgumentException("Unknown event type: $type")
    }

    companion object {
        fun fromEvent(event: Event): EventDto = when (event) {
            is Event.BookSnapshot -> EventDto(
                type = "BookSnapshot",
                symbol = event.symbol,
                bids = event.bids.map { PriceLevelDto.fromPriceLevel(it) },
                asks = event.asks.map { PriceLevelDto.fromPriceLevel(it) },
                checksum = event.checksum,
                timestamp = event.timestamp,
            )
            is Event.BookUpdate -> EventDto(
                type = "BookUpdate",
                symbol = event.symbol,
                bids = event.bids.map { PriceLevelDto.fromPriceLevel(it) },
                asks = event.asks.map { PriceLevelDto.fromPriceLevel(it) },
                checksum = event.checksum,
                timestamp = event.timestamp,
            )
            is Event.OrderAccepted -> EventDto(
                type = "OrderAccepted",
                orderId = event.orderId,
                clOrdId = event.clOrdId,
                symbol = event.symbol,
                side = event.side.name,
                price = event.price.toPlainString(),
                qty = event.qty.toPlainString(),
                timestamp = event.timestamp,
            )
            is Event.OrderFill -> EventDto(
                type = "OrderFill",
                orderId = event.orderId,
                clOrdId = event.clOrdId,
                execId = event.execId,
                symbol = event.symbol,
                side = event.side.name,
                fillQty = event.fillQty.toPlainString(),
                fillPrice = event.fillPrice.toPlainString(),
                cumQty = event.cumQty.toPlainString(),
                leavesQty = event.leavesQty.toPlainString(),
                timestamp = event.timestamp,
            )
            is Event.OrderCanceled -> EventDto(
                type = "OrderCanceled",
                orderId = event.orderId,
                clOrdId = event.clOrdId,
                reason = event.reason,
                timestamp = event.timestamp,
            )
            is Event.OrderAmended -> EventDto(
                type = "OrderAmended",
                oldOrderId = event.oldOrderId,
                newOrderId = event.newOrderId,
                clOrdId = event.clOrdId,
                newPrice = event.newPrice.toPlainString(),
                newQty = event.newQty?.toPlainString(),
                timestamp = event.timestamp,
            )
            is Event.OrderRejected -> EventDto(
                type = "OrderRejected",
                clOrdId = event.clOrdId,
                reason = event.reason,
                timestamp = event.timestamp,
            )
            is Event.Connected -> EventDto(
                type = "Connected",
                connectionType = event.connectionType.name,
                timestamp = event.timestamp,
            )
            is Event.Disconnected -> EventDto(
                type = "Disconnected",
                connectionType = event.connectionType.name,
                reason = event.reason,
                timestamp = event.timestamp,
            )
            is Event.Heartbeat -> EventDto(
                type = "Heartbeat",
                timestamp = event.timestamp,
            )
            is Event.Shutdown -> EventDto(type = "Shutdown")
        }
    }
}

@Serializable
private data class PriceLevelDto(
    val price: String,
    val qty: String,
) {
    fun toPriceLevel(): PriceLevel = PriceLevel(
        price = BigDecimal(price),
        qty = BigDecimal(qty),
    )

    companion object {
        fun fromPriceLevel(level: PriceLevel): PriceLevelDto = PriceLevelDto(
            price = level.price.toPlainString(),
            qty = level.qty.toPlainString(),
        )
    }
}
