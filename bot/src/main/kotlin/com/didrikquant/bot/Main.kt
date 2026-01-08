package com.didrikquant.bot

import com.didrikquant.core.Command
import com.didrikquant.kraken.KrakenConfig
import com.didrikquant.kraken.KrakenPrivateWs
import com.didrikquant.kraken.KrakenPublicWs
import com.didrikquant.kraken.KrakenRestClient
import com.didrikquant.replay.recorder.RecorderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.math.BigDecimal
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

public fun main(args: Array<String>): Unit =
    runBlocking {
        val parsedArgs = parseArgs(args)
        val dryRun = parsedArgs.dryRun

        logger.info {
            buildString {
                append("Starting Kraken Futures HFT Market Maker")
                if (dryRun) append(" [DRY-RUN MODE]")
                parsedArgs.epochTradeCount?.let { append(" [EPOCH: $it trades]") }
                parsedArgs.epochMaxDurationMs?.let { append(" [MAX: ${it}ms]") }
            }
        }

        val krakenConfig =
            if (dryRun) {
                KrakenConfig.forPublicOnly()
            } else {
                KrakenConfig.fromEnv()
            }

        val botConfig =
            BotConfig(
                symbol = parsedArgs.symbol,
                spreadBps = 10,
                orderSize = BigDecimal("10"),
                requoteIntervalMs = 2000,
                dryRun = dryRun,
                epochTradeCount = parsedArgs.epochTradeCount,
                epochMaxDurationMs = parsedArgs.epochMaxDurationMs,
                strategyClass = parsedArgs.strategyClass,
            )

        logger.info { "Config: symbol=${botConfig.symbol}, strategy=${botConfig.strategyClass}" }

        val recorderConfig = RecorderConfig.fromEnv()
        logger.info { "Recording to: ${recorderConfig.dataDir}" }

        val restClient: KrakenRestClient? = if (dryRun) null else KrakenRestClient(krakenConfig)

        if (!dryRun && restClient != null) {
            logger.info { "Verifying API credentials..." }
            try {
                val accounts = restClient.getAccounts()
                val result = accounts["result"]?.jsonPrimitive?.contentOrNull
                if (result != "success") {
                    val error = accounts["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                    logger.error { "API authentication failed: $error" }
                    restClient.close()
                    return@runBlocking
                }
                val accountsData = accounts["accounts"]?.jsonObject
                if (accountsData == null) {
                    logger.error { "No accounts found" }
                    restClient.close()
                    return@runBlocking
                }
                // Use multi-collateral "flex" account portfolio value
                val flexAccount = accountsData["flex"]?.jsonObject
                val balance = flexAccount?.get("portfolioValue")?.jsonPrimitive?.doubleOrNull ?: 0.0
                logger.info { "API verified. Account portfolio value: ${"%.2f".format(balance)} USD" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to verify API credentials" }
                restClient.close()
                return@runBlocking
            }
        }

        val pipeline = Pipeline(botConfig)
        val disruptor = pipeline.start(recorderConfig)
        val ringBuffer = disruptor.ringBuffer

        val publicWs = KrakenPublicWs(krakenConfig, ringBuffer, listOf(botConfig.symbol))
        val privateWs: KrakenPrivateWs? =
            if (dryRun) {
                null
            } else {
                KrakenPrivateWs(krakenConfig, ringBuffer, restClient!!)
            }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        publicWs.connect(scope)
        privateWs?.connect(scope)

        logger.info { "Futures WebSocket connections initiated" }

        logger.info { "Waiting for WebSocket connections to be ready..." }
        val connectTimeout = 30_000L
        val connectStart = System.currentTimeMillis()
        while (true) {
            val publicReady = publicWs.isReady()
            val privateReady = dryRun || (privateWs?.isReady() == true)

            if (publicReady && privateReady) {
                logger.info { "All WebSocket connections ready" }
                break
            }

            if (System.currentTimeMillis() - connectStart > connectTimeout) {
                logger.error { "Timeout waiting for WebSocket connections" }
                publicWs.close()
                privateWs?.close()
                pipeline.stop()
                restClient?.close()
                scope.cancel()
                return@runBlocking
            }

            delay(100)
        }

        val startTimeMs = System.currentTimeMillis()

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking {
                    logger.info { "Shutdown initiated" }
                    if (!dryRun && privateWs != null) {
                        logger.info { "Canceling all orders..." }
                        privateWs.sendCommand(Command.CancelAll(botConfig.symbol))
                        delay(1000)
                    }
                    publicWs.close()
                    privateWs?.close()
                    pipeline.cleanupOldRecordings()
                    pipeline.stop()
                    restClient?.close()
                    scope.cancel()
                    logger.info { "Shutdown complete" }
                }
            },
        )

        if (!dryRun && privateWs != null) {
            scope.launch {
                while (isActive) {
                    if (!privateWs.isReady()) {
                        delay(100)
                        continue
                    }
                    val commands = pipeline.outputHandler.drainCommands()
                    for (cmd in commands) {
                        privateWs.sendCommand(cmd)
                    }
                    delay(10)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(30000)
                val book = pipeline.orderBook
                val pos = pipeline.orderManager.getPosition()
                val openOrders = pipeline.orderManager.getOpenOrderCount()
                logger.info {
                    "Status: mid=${book.midPrice}, spread=${book.spreadBps}bps, " +
                        "position=$pos, openOrders=$openOrders"
                }
            }
        }

        val runMessage =
            buildString {
                append("Futures MM Bot running")
                if (dryRun) append(" in DRY-RUN mode (no orders will be placed)")
                botConfig.epochTradeCount?.let { append(". Will shutdown after $it trades") }
                botConfig.epochMaxDurationMs?.let { append(" (or ${it / 1000}s max)") }
                append(". Press Ctrl+C to stop.")
            }
        logger.info { runMessage }

        var exitReason = ExitReason.SUCCESS

        mainLoop@ while (scope.isActive) {
            delay(1000)

            if (!publicWs.isReady()) {
                logger.error { "Public WS disconnected, ending epoch early" }
                exitReason = ExitReason.PUBLIC_WS_DISCONNECT
                if (!dryRun && privateWs != null && privateWs.isReady()) {
                    privateWs.sendCommand(Command.CancelAll(botConfig.symbol))
                    delay(500)
                }
                break@mainLoop
            }

            if (!dryRun && privateWs != null && !privateWs.isReady()) {
                logger.error { "Private WS disconnected, ending epoch early" }
                exitReason = ExitReason.PRIVATE_WS_DISCONNECT
                break@mainLoop
            }

            if (pipeline.killSwitch.isTriggered()) {
                logger.error { "KILL SWITCH TRIGGERED: ${pipeline.killSwitch.getTriggerReason()}" }
                exitReason = ExitReason.KILL_SWITCH
                if (!dryRun && privateWs != null) {
                    privateWs.sendCommand(Command.CancelAll(botConfig.symbol))
                }
                break@mainLoop
            }

            val targetTrades = botConfig.epochTradeCount
            if (targetTrades != null) {
                val fillCount = pipeline.getFillCount()
                if (fillCount >= targetTrades) {
                    logger.info { "Epoch trade target reached ($fillCount trades). Shutting down." }
                    if (!dryRun && privateWs != null) {
                        privateWs.sendCommand(Command.CancelAll(botConfig.symbol))
                        delay(1000)
                    }
                    break@mainLoop
                }
            }

            val maxMs = botConfig.epochMaxDurationMs
            if (maxMs != null) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                if (elapsed >= maxMs) {
                    logger.info { "Epoch max duration reached (${elapsed}ms). Shutting down." }
                    if (!dryRun && privateWs != null) {
                        privateWs.sendCommand(Command.CancelAll(botConfig.symbol))
                        delay(1000)
                    }
                    break@mainLoop
                }
            }
        }

        publicWs.close()
        privateWs?.close()
        pipeline.stop()
        restClient?.close()
        scope.cancel()

        if (exitReason != ExitReason.SUCCESS) {
            logger.warn { "Exiting with code ${exitReason.code} (${exitReason.name})" }
            exitProcess(exitReason.code)
        }
    }

private data class ParsedArgs(
    val dryRun: Boolean = false,
    val symbol: String = "PF_XRPUSD",
    val epochTradeCount: Int? = null,
    val epochMaxDurationMs: Long? = null,
    val strategyClass: String = "SimpleMarketMaker",
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var dryRun = false
    var symbol = "PF_XRPUSD"
    var epochTradeCount: Int? = null
    var epochMaxDurationMs: Long? = null
    var strategyClass = "SimpleMarketMaker"

    for (arg in args) {
        when {
            arg == "--dry-run" -> dryRun = true
            arg.startsWith("--symbol=") -> symbol = arg.substringAfter("=")
            arg.startsWith("--epoch-trades=") -> epochTradeCount = arg.substringAfter("=").toIntOrNull()
            arg.startsWith("--epoch-max-duration=") -> epochMaxDurationMs = arg.substringAfter("=").toLongOrNull()
            arg.startsWith("--strategy=") -> strategyClass = arg.substringAfter("=")
        }
    }

    return ParsedArgs(
        dryRun = dryRun,
        symbol = symbol,
        epochTradeCount = epochTradeCount,
        epochMaxDurationMs = epochMaxDurationMs,
        strategyClass = strategyClass,
    )
}
