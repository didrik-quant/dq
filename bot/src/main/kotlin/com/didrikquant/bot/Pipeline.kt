package com.didrikquant.bot

import com.didrikquant.bot.handlers.BookHandler
import com.didrikquant.bot.handlers.CleanupHandler
import com.didrikquant.bot.handlers.CommandOutputHandler
import com.didrikquant.bot.handlers.DryRunOutputHandler
import com.didrikquant.bot.handlers.ExecutionHandler
import com.didrikquant.bot.handlers.OutputHandler
import com.didrikquant.bot.handlers.RiskHandler
import com.didrikquant.bot.handlers.StrategyHandler
import com.didrikquant.core.OrderBook
import com.didrikquant.core.disruptor.DisruptorConfig
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.didrikquant.replay.recorder.EventRecorder
import com.didrikquant.replay.recorder.RecorderConfig
import com.didrikquant.risk.KillSwitch
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
    public val orderBook: OrderBook = OrderBook(config.symbol)
    public val orderManager: OrderManager = OrderManager(config.symbol)
    public val killSwitch: KillSwitch = KillSwitch(config.riskConfig)

    private val strategy: Strategy = createStrategy(config)
    private val riskChecker = RiskChecker(config.riskConfig)

    public lateinit var bookHandler: BookHandler
        private set
    public lateinit var strategyHandler: StrategyHandler
        private set
    public lateinit var riskHandler: RiskHandler
        private set
    public lateinit var executionHandler: ExecutionHandler
        private set
    public lateinit var outputHandler: CommandOutputHandler
        private set
    public var eventRecorder: EventRecorder? = null
        private set

    private lateinit var disruptor: Disruptor<MutableEvent>

    public fun start(recorderConfig: RecorderConfig? = null): Disruptor<MutableEvent> {
        bookHandler = BookHandler(orderBook)
        strategyHandler =
            StrategyHandler(
                strategy = strategy,
                orderBook = orderBook,
                orderManager = orderManager,
                killSwitch = killSwitch,
                requoteIntervalMs = config.requoteIntervalMs,
            )
        riskHandler =
            RiskHandler(
                riskChecker = riskChecker,
                orderManager = orderManager,
                symbol = config.symbol,
            )
        executionHandler = ExecutionHandler(orderManager, killSwitch)
        outputHandler =
            if (config.dryRun) {
                DryRunOutputHandler(orderBook)
            } else {
                OutputHandler()
            }

        val handlers =
            mutableListOf(
                bookHandler,
                strategyHandler,
                riskHandler,
                executionHandler,
                outputHandler,
            )

        if (recorderConfig != null) {
            eventRecorder = EventRecorder(recorderConfig)
            handlers.add(eventRecorder!!)
        }

        // CleanupHandler MUST be last - clears event after all processing
        handlers.add(CleanupHandler())

        disruptor =
            DisruptorConfig.createDisruptor(
                handlers = handlers.toTypedArray(),
                onError = { ex ->
                    killSwitch.manualTrigger("Disruptor exception: ${ex.message}")
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

    public fun getFillCount(): Int = executionHandler.fillCount

    private companion object {
        fun createStrategy(config: BotConfig): Strategy =
            when (config.strategyClass) {
                "SimpleMarketMaker" ->
                    SimpleMarketMaker(
                        spreadBps = config.spreadBps,
                        orderSize = config.orderSize,
                        tickSize = config.tickSize,
                    )
                "AgentXrpStrategy" ->
                    AgentXrpStrategy(
                        spreadBps = config.spreadBps,
                        orderSize = config.orderSize,
                        tickSize = config.tickSize,
                    )
                else -> error("Unknown strategy class: ${config.strategyClass}")
            }
    }
}
