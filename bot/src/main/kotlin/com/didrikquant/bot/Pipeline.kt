package com.didrikquant.bot

import com.didrikquant.bot.handlers.BookHandler
import com.didrikquant.bot.handlers.CleanupHandler
import com.didrikquant.bot.handlers.CommandOutputHandler
import com.didrikquant.bot.handlers.DryRunOutputHandler
import com.didrikquant.bot.handlers.EpochGuardHandler
import com.didrikquant.bot.handlers.MonitoringHandler
import com.didrikquant.bot.handlers.OutputHandler
import com.didrikquant.bot.handlers.TradingHandler
import com.didrikquant.core.OrderBook
import com.didrikquant.core.disruptor.DisruptorConfig
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.kraken.KrakenRestClient
import com.didrikquant.replay.recorder.EventRecorder
import com.didrikquant.replay.recorder.RecorderConfig
import com.didrikquant.risk.RiskChecker
import com.didrikquant.strategy.AgentXrpStrategy
import com.didrikquant.strategy.SimpleMarketMaker
import com.didrikquant.strategy.Strategy
import com.lmax.disruptor.dsl.Disruptor
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

public class Pipeline(
    private val config: BotConfig,
) {
    private val orderBook = OrderBook(config.symbol)
    private val strategy: Strategy = createStrategy(config)
    private val riskChecker = RiskChecker(config.riskConfig)

    private lateinit var disruptor: Disruptor<MutableEvent>
    private var eventRecorder: EventRecorder? = null
    private var commandSender: CommandSender? = null

    /**
     * Start the pipeline with the new handler chain:
     * BookHandler -> TradingHandler -> OutputHandler -> EpochGuardHandler -> MonitoringHandler -> CleanupHandler
     */
    public fun start(
        recorderConfig: RecorderConfig? = null,
        restClient: KrakenRestClient? = null,
        onShutdown: (() -> Unit)? = null,
    ): Disruptor<MutableEvent> {
        commandSender = CommandSender(restClient, config.symbol)

        val bookHandler = BookHandler(orderBook)
        val tradingHandler = TradingHandler(
            strategy = strategy,
            riskChecker = riskChecker,
            symbol = config.symbol,
            requoteIntervalMs = config.requoteIntervalMs,
            maxLossUsd = config.riskConfig.maxLossUsd,
        )
        val outputHandler: CommandOutputHandler = if (config.dryRun) {
            DryRunOutputHandler()
        } else {
            OutputHandler(commandSender!!)
        }
        val epochGuardHandler = EpochGuardHandler(config.epochTradeCount, config.epochMaxDurationMs)
        val monitoringHandler = MonitoringHandler(logEveryNEvents = 1000)

        val handlers = mutableListOf(
            bookHandler,
            tradingHandler,
            outputHandler,
            epochGuardHandler,
            monitoringHandler,
        )

        if (recorderConfig != null) {
            eventRecorder = EventRecorder(recorderConfig)
            handlers.add(eventRecorder!!)
        }

        // CleanupHandler MUST be last - clears event after all processing
        handlers.add(CleanupHandler())

        disruptor = DisruptorConfig.createDisruptor(
            handlers = handlers.toTypedArray(),
            onFatalError = { ex ->
                logger.error(ex) { "Fatal error - canceling orders and exiting" }
                commandSender?.cancelAllAndShutdown()
                onShutdown?.invoke()
            },
        )

        disruptor.start()
        logger.info { "Disruptor pipeline started" }

        return disruptor
    }

    public fun stop() {
        disruptor.shutdown()
        eventRecorder?.close()
        logger.info { "Disruptor pipeline stopped" }
    }

    public fun cleanupOldRecordings() {
        eventRecorder?.cleanup()
    }

    public fun getCommandSender(): CommandSender? = commandSender

    private companion object {
        fun createStrategy(config: BotConfig): Strategy =
            when (config.strategyClass) {
                "SimpleMarketMaker" -> {
                    SimpleMarketMaker(
                        spreadBps = config.spreadBps,
                        orderSize = config.orderSize,
                        tickSize = config.tickSize,
                        maxPosition = config.maxPosition,
                    )
                }

                "AgentXrpStrategy" -> {
                    AgentXrpStrategy(
                        orderSize = config.orderSize,
                        tickSize = config.tickSize,
                        maxPosition = config.maxPosition,
                    )
                }

                else -> {
                    error("Unknown strategy class: ${config.strategyClass}")
                }
            }
    }
}
