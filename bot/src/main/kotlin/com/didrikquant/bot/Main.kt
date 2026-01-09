package com.didrikquant.bot

import com.didrikquant.kraken.KrakenConfig
import com.didrikquant.kraken.KrakenPrivateWs
import com.didrikquant.kraken.KrakenPublicWs
import com.didrikquant.kraken.KrakenRestClient
import com.didrikquant.replay.recorder.RecorderConfig
import com.didrikquant.risk.RiskConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

public fun main(args: Array<String>): Unit = runBlocking {
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

    val krakenConfig = if (dryRun) {
        KrakenConfig.forPublicOnly()
    } else {
        KrakenConfig.fromEnv()
    }

    val recorderConfig = RecorderConfig.fromEnv()
    logger.info { "Recording to: ${recorderConfig.dataDir}" }

    val restClient: KrakenRestClient? = if (dryRun) null else KrakenRestClient(krakenConfig)

    val marginLimits: MarginLimits = if (dryRun || restClient == null) {
        logger.info { "Dry-run mode: using default margin limits" }
        MarginLimits(
            maxPosition = BigDecimal("100"),
            orderSize = BigDecimal("10"),
        )
    } else {
        logger.info { "Verifying API credentials and calculating margin limits..." }
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
            val flexAccount = accountsData["flex"]?.jsonObject
            val portfolioValue = flexAccount?.get("portfolioValue")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val availableMargin = flexAccount?.get("availableMargin")?.jsonPrimitive?.doubleOrNull ?: portfolioValue

            logger.info {
                "API verified. Portfolio: ${"%.2f".format(portfolioValue)} USD, " +
                    "Available margin: ${"%.2f".format(availableMargin)} USD"
            }

            calculateMarginLimits(availableMargin)
        } catch (e: Exception) {
            logger.error(e) { "Failed to verify API credentials" }
            restClient.close()
            return@runBlocking
        }
    }

    logger.info { "Margin limits: maxPosition=${marginLimits.maxPosition}, orderSize=${marginLimits.orderSize}" }

    val botConfig = BotConfig(
        symbol = parsedArgs.symbol,
        spreadBps = 10,
        orderSize = marginLimits.orderSize,
        maxPosition = marginLimits.maxPosition,
        requoteIntervalMs = 2000,
        dryRun = dryRun,
        epochTradeCount = parsedArgs.epochTradeCount,
        epochMaxDurationMs = parsedArgs.epochMaxDurationMs,
        strategyClass = parsedArgs.strategyClass,
        riskConfig = RiskConfig(
            maxPositionSize = marginLimits.maxPosition,
            maxOrderSize = marginLimits.orderSize,
        ),
    )

    logger.info { "Config: symbol=${botConfig.symbol}, strategy=${botConfig.strategyClass}" }

    // Shutdown coordinator
    val shutdownLatch = CountDownLatch(1)
    val exitCode = AtomicInteger(0)

    val pipeline = Pipeline(botConfig)
    val disruptor = pipeline.start(
        recorderConfig = recorderConfig,
        restClient = restClient,
        onShutdown = {
            exitCode.set(1)
            shutdownLatch.countDown()
        },
    )
    val ringBuffer = disruptor.ringBuffer

    val publicWs = KrakenPublicWs(krakenConfig, ringBuffer, listOf(botConfig.symbol))
    val privateWs: KrakenPrivateWs? = if (dryRun) {
        null
    } else {
        KrakenPrivateWs(krakenConfig, ringBuffer, restClient!!)
    }

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    publicWs.connect(scope)
    privateWs?.connect(scope)

    logger.info { "Futures WebSocket connections initiated" }

    // Wait for WebSocket connections to be ready
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

    // Shutdown hook for Ctrl+C - just signals the watchdog
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutdown signal received" }
        shutdownLatch.countDown()
    })

    // Watchdog thread - handles cleanup with hard timeout
    Thread {
        shutdownLatch.await(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        logger.info { "Shutdown initiated - cleanup starting (5s timeout)" }

        val cleanupStart = System.currentTimeMillis()
        runCatching {
            scope.cancel()
            publicWs.close()
            privateWs?.close()
            pipeline.cleanupOldRecordings()
            pipeline.stop()
            restClient?.close()
        }.onFailure { ex ->
            logger.error(ex) { "Error during cleanup" }
        }

        val elapsed = System.currentTimeMillis() - cleanupStart
        logger.info { "Cleanup completed in ${elapsed}ms - exiting with code ${exitCode.get()}" }
        Runtime.getRuntime().halt(exitCode.get())
    }.apply {
        isDaemon = true
        name = "shutdown-watchdog"
        start()
    }

    // Hard timeout thread - forces exit if watchdog hangs
    Thread {
        if (!shutdownLatch.await(Long.MAX_VALUE, TimeUnit.MILLISECONDS)) return@Thread
        Thread.sleep(5000)
        logger.error { "Shutdown timeout exceeded - forcing exit" }
        Runtime.getRuntime().halt(exitCode.get())
    }.apply {
        isDaemon = true
        name = "shutdown-timeout"
        start()
    }

    // Command sending coroutine - polls commands from queue and sends via WebSocket
    // This is required because OutputHandler runs on Disruptor thread (non-coroutine)
    // and WebSocket sendCommand is a suspend function
    val commandSender = pipeline.getCommandSender()
    if (!dryRun && privateWs != null && commandSender != null) {
        scope.launch {
            while (isActive && !commandSender.isShutdown()) {
                if (!privateWs.isReady()) {
                    delay(100)
                    continue
                }
                val cmd = commandSender.poll()
                if (cmd != null) {
                    privateWs.sendCommand(cmd)
                } else {
                    delay(10)
                }
            }
        }
    }

    val runMessage = buildString {
        append("Futures MM Bot running")
        if (dryRun) append(" in DRY-RUN mode (no orders will be placed)")
        botConfig.epochTradeCount?.let { append(". Will shutdown after $it trades") }
        botConfig.epochMaxDurationMs?.let { append(" (or ${it / 1000}s max)") }
        append(". Press Ctrl+C to stop.")
    }
    logger.info { runMessage }

    // Block forever - watchdog handles cleanup and calls halt() to exit
    shutdownLatch.await()
    Thread.sleep(Long.MAX_VALUE)
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

private data class MarginLimits(
    val maxPosition: BigDecimal,
    val orderSize: BigDecimal,
)

private fun calculateMarginLimits(availableMargin: Double): MarginLimits {
    val usableMargin = availableMargin * 0.5

    val initialMarginRate = 0.02
    val xrpPrice = 2.30

    val maxNotional = usableMargin / initialMarginRate
    val maxPos = (maxNotional / xrpPrice).toBigDecimal().setScale(0, RoundingMode.DOWN)
    val ordSize = (maxPos / BigDecimal("5")).max(BigDecimal("1"))

    return MarginLimits(
        maxPosition = maxPos.max(BigDecimal.ZERO),
        orderSize = ordSize,
    )
}
