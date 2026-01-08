package com.didrikquant.kraken

import com.didrikquant.core.*
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.kraken.model.*
import com.lmax.disruptor.RingBuffer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

public class KrakenPublicWs(
    private val config: KrakenConfig,
    private val ringBuffer: RingBuffer<MutableEvent>,
    private val symbols: List<String>,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private var session: WebSocketSession? = null
    private var job: Job? = null

    @Volatile
    private var ready: Boolean = false

    public fun isReady(): Boolean = ready

    public suspend fun connect(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                try {
                    client.webSocket(config.wsUrl) {
                        session = this
                        logger.info { "Futures WS connected to ${config.wsUrl}" }
                        publishEvent(Event.Connected(ConnectionType.PUBLIC, System.currentTimeMillis()))

                        subscribeToBook()
                        ready = true
                        logger.info { "Public WS ready" }
                        handleMessages()
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Futures WS error" }
                    ready = false
                    publishEvent(
                        Event.Disconnected(
                            ConnectionType.PUBLIC,
                            e.message ?: "Unknown error",
                            System.currentTimeMillis(),
                        ),
                    )
                }

                ready = false
                logger.info { "Reconnecting Futures WS in 5 seconds..." }
                delay(5000)
            }
        }
    }

    private suspend fun WebSocketSession.subscribeToBook() {
        val request = WsSubscribeRequest(
            event = "subscribe",
            feed = "book",
            productIds = symbols,
        )
        send(json.encodeToString(request))
        logger.info { "Subscribed to book for $symbols" }
    }

    private suspend fun WebSocketSession.handleMessages() {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> {
                    val text = frame.readText()
                    processMessage(text)
                }
                is Frame.Ping -> send(Frame.Pong(frame.data))
                else -> {}
            }
        }
    }

    private fun processMessage(text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val obj = jsonElement.jsonObject

            val feed = obj["feed"]?.jsonPrimitive?.contentOrNull
            when (feed) {
                "book_snapshot" -> processBookSnapshot(obj)
                "book" -> processBookDelta(obj)
                "heartbeat" -> publishEvent(Event.Heartbeat(System.currentTimeMillis()))
            }

            val event = obj["event"]?.jsonPrimitive?.contentOrNull
            if (event == "subscribed") {
                logger.info { "Subscription confirmed: $text" }
            } else if (event == "error") {
                logger.error { "WS error: $text" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse message: $text" }
        }
    }

    private fun processBookSnapshot(obj: JsonObject) {
        val productId = obj["product_id"]?.jsonPrimitive?.contentOrNull ?: return
        val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0L
        val timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

        val bids = obj["bids"]?.jsonArray?.mapNotNull { entry ->
            val entryObj = entry.jsonObject
            val price = entryObj["price"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val qty = entryObj["qty"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            PriceLevel(BigDecimal.valueOf(price), BigDecimal.valueOf(qty))
        } ?: emptyList()

        val asks = obj["asks"]?.jsonArray?.mapNotNull { entry ->
            val entryObj = entry.jsonObject
            val price = entryObj["price"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val qty = entryObj["qty"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            PriceLevel(BigDecimal.valueOf(price), BigDecimal.valueOf(qty))
        } ?: emptyList()

        publishEvent(
            Event.BookSnapshot(
                symbol = productId,
                bids = bids,
                asks = asks,
                checksum = seq,
                timestamp = timestamp,
            ),
        )
        logger.debug { "Book snapshot: $productId, ${bids.size} bids, ${asks.size} asks" }
    }

    private fun processBookDelta(obj: JsonObject) {
        val productId = obj["product_id"]?.jsonPrimitive?.contentOrNull ?: return
        val side = obj["side"]?.jsonPrimitive?.contentOrNull ?: return
        val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0L
        val price = obj["price"]?.jsonPrimitive?.doubleOrNull ?: return
        val qty = obj["qty"]?.jsonPrimitive?.doubleOrNull ?: return
        val timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

        val level = PriceLevel(BigDecimal.valueOf(price), BigDecimal.valueOf(qty))
        val (bids, asks) = if (side == "buy") {
            listOf(level) to emptyList()
        } else {
            emptyList<PriceLevel>() to listOf(level)
        }

        publishEvent(
            Event.BookUpdate(
                symbol = productId,
                bids = bids,
                asks = asks,
                checksum = seq,
                timestamp = timestamp,
            ),
        )
    }

    private fun publishEvent(event: Event) {
        val sequence = ringBuffer.next()
        try {
            val mutableEvent = ringBuffer[sequence]
            mutableEvent.clear()
            mutableEvent.event = event
        } finally {
            ringBuffer.publish(sequence)
        }
    }

    public fun close() {
        job?.cancel()
        client.close()
    }
}
