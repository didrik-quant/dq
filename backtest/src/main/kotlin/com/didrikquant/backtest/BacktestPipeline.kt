package com.didrikquant.backtest

import com.didrikquant.core.Command
import com.didrikquant.core.Event
import com.didrikquant.core.OrderBook
import com.didrikquant.core.disruptor.DisruptorConfig
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.didrikquant.replay.metrics.PnLTracker
import com.didrikquant.replay.metrics.ReplayMetrics
import com.didrikquant.replay.simulator.ExecutionSimulator
import com.didrikquant.risk.KillSwitch
import com.didrikquant.risk.RiskCheckResult
import com.didrikquant.risk.RiskChecker
import com.didrikquant.strategy.OrderIntent
import com.didrikquant.strategy.SimpleMarketMaker
import com.didrikquant.strategy.Strategy
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.dsl.Disruptor
import mu.KotlinLogging
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

public class BacktestPipeline(private val config: BacktestConfig) {

    public val orderBook: OrderBook = OrderBook(config.symbol)
    public val orderManager: OrderManager = OrderManager(config.symbol)
    public val killSwitch: KillSwitch = KillSwitch(config.riskConfig)
    public val pnlTracker: PnLTracker = PnLTracker(config.symbol, config.feeRate)
    public val metrics: ReplayMetrics = ReplayMetrics()

    private val strategy: Strategy = SimpleMarketMaker(
        spreadBps = config.spreadBps,
        orderSize = config.orderSize,
        tickSize = config.tickSize,
    )
    private val riskChecker = RiskChecker(config.riskConfig)

    private lateinit var disruptor: Disruptor<MutableEvent>
    public lateinit var simulator: ExecutionSimulator
        private set

    private val currentTimestamp = AtomicLong(0)
    private val pendingIntents = AtomicReference<List<OrderIntent>>(emptyList())
    private val lastQuoteTime = AtomicLong(0)

    public fun start(): Disruptor<MutableEvent> {
        disruptor = DisruptorConfig.createDisruptor(
            handlers = arrayOf(
                BookHandler(),
                StrategyHandler(),
                RiskHandler(),
                ExecutionHandler(),
                MetricsHandler(),
            ),
        )

        simulator = ExecutionSimulator(disruptor.ringBuffer, feeRate = config.feeRate)

        disruptor.start()
        logger.info { "Backtest pipeline started for ${config.symbol}" }

        return disruptor
    }

    public fun stop() {
        disruptor.shutdown()
        logger.info { "Backtest pipeline stopped" }
    }

    public fun processCommand(command: Command) {
        simulator.processCommand(command, currentTimestamp.get())
    }

    private inner class BookHandler : EventHandler<MutableEvent> {
        override fun onEvent(event: MutableEvent, sequence: Long, endOfBatch: Boolean) {
            when (val e = event.event) {
                is Event.BookSnapshot -> {
                    orderBook.applySnapshot(e.bids, e.asks, sequence)
                    currentTimestamp.set(e.timestamp)
                    simulator.onBookUpdate(orderBook, e.timestamp)
                }
                is Event.BookUpdate -> {
                    orderBook.applyUpdate(e.bids, e.asks, sequence)
                    currentTimestamp.set(e.timestamp)
                    simulator.onBookUpdate(orderBook, e.timestamp)
                }
                else -> {}
            }
        }
    }

    private inner class StrategyHandler : EventHandler<MutableEvent> {
        override fun onEvent(event: MutableEvent, sequence: Long, endOfBatch: Boolean) {
            when (event.event) {
                is Event.BookSnapshot, is Event.BookUpdate -> {
                    if (killSwitch.isTriggered()) return

                    val now = currentTimestamp.get()
                    if (now - lastQuoteTime.get() < config.requoteIntervalMs) return

                    if (!orderBook.isValid()) return

                    val position = orderManager.getPosition()
                    val intents = strategy.onOrderBook(orderBook, position)

                    if (intents.isNotEmpty()) {
                        pendingIntents.set(intents)
                        lastQuoteTime.set(now)
                    }
                }
                else -> {}
            }
        }
    }

    private inner class RiskHandler : EventHandler<MutableEvent> {
        override fun onEvent(event: MutableEvent, sequence: Long, endOfBatch: Boolean) {
            val intents = pendingIntents.getAndSet(emptyList())
            if (intents.isEmpty()) return

            val commands = mutableListOf<Command>()

            for (order in orderManager.getOpenOrders()) {
                commands.add(Command.CancelOrder(order.orderId))
            }

            for (intent in intents) {
                val checkResult = riskChecker.check(
                    intent = intent,
                    currentPosition = orderManager.getPosition(),
                    openOrderCount = orderManager.getOpenOrderCount(),
                )
                if (checkResult !is RiskCheckResult.Approved) continue

                val clOrdId = orderManager.generateClOrdId()
                orderManager.registerPendingOrder(clOrdId, intent.side, intent.price, intent.qty)

                commands.add(
                    Command.PlaceOrder(
                        clOrdId = clOrdId,
                        symbol = config.symbol,
                        side = intent.side,
                        price = intent.price,
                        qty = intent.qty,
                    ),
                )
            }

            for (cmd in commands) {
                processCommand(cmd)
            }
        }
    }

    private inner class ExecutionHandler : EventHandler<MutableEvent> {
        override fun onEvent(event: MutableEvent, sequence: Long, endOfBatch: Boolean) {
            when (val e = event.event) {
                is Event.OrderAccepted -> orderManager.onOrderAccepted(e)
                is Event.OrderFill -> {
                    orderManager.onOrderFill(e)
                    pnlTracker.onFill(e)
                    metrics.updatePosition(orderManager.getPosition())
                }
                is Event.OrderCanceled -> orderManager.onOrderCanceled(e)
                else -> {}
            }
        }
    }

    private inner class MetricsHandler : EventHandler<MutableEvent> {
        override fun onEvent(event: MutableEvent, sequence: Long, endOfBatch: Boolean) {
            val e = event.event ?: return
            metrics.record(e)
        }
    }
}
