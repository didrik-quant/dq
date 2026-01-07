package com.didrikquant.bot

import com.didrikquant.core.Command
import com.didrikquant.kraken.KrakenConfig
import com.didrikquant.kraken.KrakenPrivateWs
import com.didrikquant.kraken.KrakenPublicWs
import com.didrikquant.kraken.KrakenRestClient
import com.didrikquant.replay.recorder.RecorderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

public fun main(args: Array<String>): Unit =
    runBlocking {
        val parsedArgs = parseArgs(args)
        val dryRun = parsedArgs.dryRun

        logger.info {
            buildString {
                append("Starting Kraken Futures HFT Market Maker")
                if (dryRun) append(" [DRY-RUN MODE]")
                parsedArgs.epochDurationMs?.let { append(" [EPOCH: ${it}ms]") }
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
                epochDurationMs = parsedArgs.epochDurationMs,
                strategyClass = parsedArgs.strategyClass,
            )

        logger.info { "Config: symbol=${botConfig.symbol}, strategy=${botConfig.strategyClass}" }

        val recorderConfig = RecorderConfig.fromEnv()
        logger.info { "Recording to: ${recorderConfig.dataDir}" }

        val restClient: KrakenRestClient? = if (dryRun) null else KrakenRestClient(krakenConfig)

        if (!dryRun && restClient != null) {
            logger.info { "Fetching account info..." }
            try {
                val accounts = restClient.getAccounts()
                logger.info { "Accounts: $accounts" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch accounts" }
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
                botConfig.epochDurationMs?.let { append(". Will shutdown after ${it / 1000}s") }
                append(". Press Ctrl+C to stop.")
            }
        logger.info { runMessage }

        while (scope.isActive) {
            delay(1000)

            if (pipeline.killSwitch.isTriggered()) {
                logger.error { "KILL SWITCH TRIGGERED: ${pipeline.killSwitch.getTriggerReason()}" }
                if (!dryRun && privateWs != null) {
                    privateWs.sendCommand(Command.CancelAll(botConfig.symbol))
                }
                break
            }

            botConfig.epochDurationMs?.let { epochMs ->
                val elapsed = System.currentTimeMillis() - startTimeMs
                if (elapsed >= epochMs) {
                    logger.info { "Epoch duration reached (${elapsed}ms). Shutting down." }
                    if (!dryRun && privateWs != null) {
                        privateWs.sendCommand(Command.CancelAll(botConfig.symbol))
                        delay(1000)
                    }
                    break
                }
            }
        }

        publicWs.close()
        privateWs?.close()
        pipeline.stop()
        restClient?.close()
        scope.cancel()
    }

private data class ParsedArgs(
    val dryRun: Boolean = false,
    val symbol: String = "PF_XRPUSD",
    val epochDurationMs: Long? = null,
    val strategyClass: String = "SimpleMarketMaker",
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var dryRun = false
    var symbol = "PF_XRPUSD"
    var epochDurationMs: Long? = null
    var strategyClass = "SimpleMarketMaker"

    for (arg in args) {
        when {
            arg == "--dry-run" -> dryRun = true
            arg.startsWith("--symbol=") -> symbol = arg.substringAfter("=")
            arg.startsWith("--epoch-duration=") -> epochDurationMs = arg.substringAfter("=").toLongOrNull()
            arg.startsWith("--strategy=") -> strategyClass = arg.substringAfter("=")
        }
    }

    return ParsedArgs(
        dryRun = dryRun,
        symbol = symbol,
        epochDurationMs = epochDurationMs,
        strategyClass = strategyClass,
    )
}
