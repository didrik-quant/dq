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
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

public class KrakenPrivateWs(
    private val config: KrakenConfig,
    private val ringBuffer: RingBuffer<MutableEvent>,
    private val restClient: KrakenRestClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private var session: WebSocketSession? = null
    private var job: Job? = null
    private var challenge: String? = null

    private val commandChannel = Channel<Command>(Channel.BUFFERED)

    public suspend fun connect(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                try {
                    client.webSocket(config.wsUrl) {
                        session = this
                        logger.info { "Private Futures WS connected" }

                        requestChallenge()
                        handleInitialMessages()

                        if (challenge != null) {
                            subscribeToFeeds()
                            publishEvent(Event.Connected(ConnectionType.PRIVATE, System.currentTimeMillis()))

                            coroutineScope {
                                launch { handleMessages() }
                                launch { processCommands() }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Private Futures WS error" }
                    publishEvent(
                        Event.Disconnected(
                            ConnectionType.PRIVATE,
                            e.message ?: "Unknown error",
                            System.currentTimeMillis(),
                        ),
                    )
                }

                logger.info { "Reconnecting private Futures WS in 5 seconds..." }
                delay(5000)
            }
        }
    }

    private suspend fun WebSocketSession.requestChallenge() {
        val request = WsChallengeRequest(apiKey = config.apiKey)
        send(json.encodeToString(request))
        logger.info { "Requested challenge" }
    }

    private suspend fun WebSocketSession.handleInitialMessages() {
        val timeout = withTimeoutOrNull(10000) {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val obj = json.parseToJsonElement(text).jsonObject
                    val event = obj["event"]?.jsonPrimitive?.contentOrNull

                    if (event == "challenge") {
                        challenge = obj["message"]?.jsonPrimitive?.contentOrNull
                        logger.info { "Received challenge" }
                        return@withTimeoutOrNull true
                    } else if (event == "error") {
                        logger.error { "Challenge error: $text" }
                        return@withTimeoutOrNull false
                    }
                }
            }
            false
        }
        if (timeout != true) {
            logger.error { "Failed to receive challenge" }
        }
    }

    private suspend fun WebSocketSession.subscribeToFeeds() {
        val signedChallenge = KrakenAuth.signChallenge(challenge!!, config.apiSecret)

        val feeds = listOf("fills", "open_orders", "open_positions")
        for (feed in feeds) {
            val request = WsAuthSubscribeRequest(
                feed = feed,
                apiKey = config.apiKey,
                originalChallenge = challenge!!,
                signedChallenge = signedChallenge,
            )
            send(json.encodeToString(request))
            logger.info { "Subscribed to $feed" }
        }
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

    private suspend fun processCommands() {
        for (command in commandChannel) {
            try {
                when (command) {
                    is Command.PlaceOrder -> executeOrder(command)
                    is Command.CancelOrder -> cancelOrder(command)
                    is Command.CancelAll -> cancelAllOrders(command)
                    is Command.AmendOrder -> logger.warn { "Amend not implemented for futures" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to execute command: $command" }
            }
        }
    }

    private suspend fun executeOrder(cmd: Command.PlaceOrder) {
        val response = restClient.sendOrder(
            symbol = cmd.symbol,
            side = if (cmd.side == Side.BUY) "buy" else "sell",
            size = cmd.qty.toDouble(),
            price = cmd.price.toDouble(),
            postOnly = cmd.postOnly,
            clientOrderId = cmd.clOrdId,
        )

        val result = response["result"]?.jsonPrimitive?.contentOrNull
        if (result == "success") {
            val sendStatus = response["sendStatus"]?.jsonObject
            val orderId = sendStatus?.get("order_id")?.jsonPrimitive?.contentOrNull ?: ""
            logger.info { "Order placed: $orderId for ${cmd.side} ${cmd.qty} @ ${cmd.price}" }
        } else {
            val error = response["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            logger.error { "Order rejected: $error" }
            publishEvent(
                Event.OrderRejected(
                    clOrdId = cmd.clOrdId,
                    reason = error,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun cancelOrder(cmd: Command.CancelOrder) {
        val response = restClient.cancelOrder(cmd.orderId)
        val result = response["result"]?.jsonPrimitive?.contentOrNull
        if (result == "success") {
            logger.info { "Order canceled: ${cmd.orderId}" }
        } else {
            logger.error { "Cancel failed: $response" }
        }
    }

    private suspend fun cancelAllOrders(cmd: Command.CancelAll) {
        val response = restClient.cancelAllOrders(cmd.symbol)
        val result = response["result"]?.jsonPrimitive?.contentOrNull
        if (result == "success") {
            logger.info { "All orders canceled" }
        } else {
            logger.error { "Cancel all failed: $response" }
        }
    }

    private fun processMessage(text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val obj = jsonElement.jsonObject

            val feed = obj["feed"]?.jsonPrimitive?.contentOrNull
            when (feed) {
                "fills", "fills_snapshot" -> processFills(obj)
                "open_orders", "open_orders_snapshot" -> processOpenOrders(obj)
                "open_positions", "open_positions_snapshot" -> processPositions(obj)
                "heartbeat" -> {}
            }

            val event = obj["event"]?.jsonPrimitive?.contentOrNull
            if (event == "subscribed") {
                logger.info { "Private subscription confirmed: ${obj["feed"]}" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse private message: $text" }
        }
    }

    private fun processFills(obj: JsonObject) {
        val fills = obj["fills"]?.jsonArray ?: return

        for (fill in fills) {
            val f = fill.jsonObject
            val instrument = f["instrument"]?.jsonPrimitive?.contentOrNull ?: continue
            val orderId = f["order_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val cliOrdId = f["cli_ord_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val fillId = f["fill_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val price = f["price"]?.jsonPrimitive?.doubleOrNull ?: continue
            val qty = f["qty"]?.jsonPrimitive?.doubleOrNull ?: continue
            val isBuy = f["buy"]?.jsonPrimitive?.booleanOrNull ?: continue
            val time = f["time"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

            publishEvent(
                Event.OrderFill(
                    orderId = orderId,
                    clOrdId = cliOrdId,
                    execId = fillId,
                    symbol = instrument,
                    side = if (isBuy) Side.BUY else Side.SELL,
                    fillQty = BigDecimal.valueOf(qty),
                    fillPrice = BigDecimal.valueOf(price),
                    cumQty = BigDecimal.valueOf(qty),
                    leavesQty = BigDecimal.ZERO,
                    timestamp = time,
                ),
            )
            logger.info { "Fill: ${if (isBuy) "BUY" else "SELL"} $qty @ $price" }
        }
    }

    private fun processOpenOrders(obj: JsonObject) {
        val orders = obj["orders"]?.jsonArray ?: return

        for (order in orders) {
            val o = order.jsonObject
            val instrument = o["instrument"]?.jsonPrimitive?.contentOrNull ?: continue
            val orderId = o["order_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val cliOrdId = o["cli_ord_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val limitPrice = o["limit_price"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val qty = o["qty"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val direction = o["direction"]?.jsonPrimitive?.intOrNull ?: 0
            val time = o["time"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

            publishEvent(
                Event.OrderAccepted(
                    orderId = orderId,
                    clOrdId = cliOrdId,
                    symbol = instrument,
                    side = if (direction > 0) Side.BUY else Side.SELL,
                    price = BigDecimal.valueOf(limitPrice),
                    qty = BigDecimal.valueOf(qty),
                    timestamp = time,
                ),
            )
        }
    }

    private fun processPositions(obj: JsonObject) {
        val positions = obj["positions"]?.jsonArray ?: return
        for (pos in positions) {
            val p = pos.jsonObject
            val instrument = p["instrument"]?.jsonPrimitive?.contentOrNull ?: continue
            val balance = p["balance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val pnl = p["pnl"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            logger.debug { "Position: $instrument balance=$balance pnl=$pnl" }
        }
    }

    public suspend fun sendCommand(command: Command) {
        commandChannel.send(command)
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
        commandChannel.close()
        client.close()
    }
}
