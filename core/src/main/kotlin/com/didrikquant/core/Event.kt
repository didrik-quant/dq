package com.didrikquant.core

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
