package com.didrikquant.core

import com.didrikquant.orderstate.Side
import java.math.BigDecimal

public sealed class Event {

    public data class BookSnapshot(
        val symbol: String,
        val bids: List<PriceLevel>,
        val asks: List<PriceLevel>,
        val checksum: Long,
        val timestamp: Long,
    ) : Event()

    public data class BookUpdate(
        val symbol: String,
        val bids: List<PriceLevel>,
        val asks: List<PriceLevel>,
        val checksum: Long,
        val timestamp: Long,
    ) : Event()

    public data class OrderAccepted(
        val orderId: String,
        val clOrdId: String,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val qty: BigDecimal,
        val timestamp: Long,
    ) : Event()

    public data class OrderFill(
        val orderId: String,
        val clOrdId: String,
        val execId: String,
        val symbol: String,
        val side: Side,
        val fillQty: BigDecimal,
        val fillPrice: BigDecimal,
        val cumQty: BigDecimal,
        val leavesQty: BigDecimal,
        val timestamp: Long,
    ) : Event()

    public data class OrderCanceled(
        val orderId: String,
        val clOrdId: String,
        val reason: String,
        val timestamp: Long,
    ) : Event()

    public data class OrderAmended(
        val oldOrderId: String,
        val newOrderId: String,
        val clOrdId: String,
        val newPrice: BigDecimal,
        val newQty: BigDecimal?,
        val timestamp: Long,
    ) : Event()

    public data class OrderRejected(
        val clOrdId: String,
        val reason: String,
        val timestamp: Long,
    ) : Event()

    public data class Connected(
        val connectionType: ConnectionType,
        val timestamp: Long,
    ) : Event()

    public data class Disconnected(
        val connectionType: ConnectionType,
        val reason: String,
        val timestamp: Long,
    ) : Event()

    public data class Heartbeat(
        val timestamp: Long,
    ) : Event()

    public object Shutdown : Event()
}

public enum class ConnectionType {
    PUBLIC,
    PRIVATE,
}
