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
        val dryRun = args.contains("--dry-run")

        logger.info {
            if (dryRun) {
                "Starting Kraken Futures HFT Market Maker [DRY-RUN MODE]"
            } else {
                "Starting Kraken Futures HFT Market Maker"
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
                symbol = "PF_XRPUSD",
                spreadBps = 10,
                orderSize = BigDecimal("10"),
                requoteIntervalMs = 2000,
                dryRun = dryRun,
            )

        logger.info { "Config: symbol=${botConfig.symbol}, spread=${botConfig.spreadBps}bps, size=${botConfig.orderSize}" }

        val recorderConfig = RecorderConfig.fromEnv()
        logger.info { "Recording to: ${recorderConfig.dataDir}" }

        val restClient: KrakenRestClient? =
            if (dryRun) {
                null
            } else {
                KrakenRestClient(krakenConfig)
            }

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

        logger.info {
            if (dryRun) {
                "Futures MM Bot running in DRY-RUN mode (no orders will be placed). Press Ctrl+C to stop."
            } else {
                "Futures MM Bot running. Press Ctrl+C to stop."
            }
        }

        while (scope.isActive) {
            delay(1000)

            if (pipeline.killSwitch.isTriggered()) {
                logger.error { "KILL SWITCH TRIGGERED: ${pipeline.killSwitch.getTriggerReason()}" }
                if (!dryRun && privateWs != null) {
                    privateWs.sendCommand(Command.CancelAll(botConfig.symbol))
                }
                break
            }
        }
    }
