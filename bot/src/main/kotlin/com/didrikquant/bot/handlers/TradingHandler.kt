package com.didrikquant.bot.handlers

import com.didrikquant.core.BotFatalException
import com.didrikquant.core.Command
import com.didrikquant.core.Event
import com.didrikquant.core.StrategyAction
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderStore
import com.didrikquant.execution.ApplyResult
import com.didrikquant.execution.CreateResult
import com.didrikquant.execution.PositionTracker
import com.didrikquant.orderstate.OrderSnapshot
import com.didrikquant.orderstate.OrderStateEvent
import com.didrikquant.risk.RiskCheckResult
import com.didrikquant.risk.RiskChecker
import com.didrikquant.strategy.Strategy
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

public class TradingHandler(
    private val strategy: Strategy,
    private val riskChecker: RiskChecker,
    private val symbol: String,
    private val requoteIntervalMs: Long,
    private val maxLossUsd: BigDecimal,
) : EventHandler<MutableEvent> {

    private val orderStore = OrderStore()
    private val positionTracker = PositionTracker()
    private val lastQuoteTime = AtomicLong(0)

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        processOrderEvents(event)

        checkMaxLoss()

        processBookEvents(event)

        event.openOrders = orderStore.activeOrders()
        event.positionSnapshot = positionTracker.snapshot()
    }

    private fun processOrderEvents(event: MutableEvent) {
        when (val e = event.event) {
            is Event.OrderAccepted -> {
                val orderEvent = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = e.clOrdId,
                    orderId = e.orderId,
                    side = e.side,
                    price = e.price,
                    qty = e.qty,
                    timestamp = e.timestamp,
                )
                applyOrderEvent(orderEvent)
                logger.info { "Order accepted: ${e.orderId} (${e.clOrdId})" }
            }
            is Event.OrderFill -> {
                val isFilled = e.leavesQty <= BigDecimal.ZERO
                val orderEvent = if (isFilled) {
                    OrderStateEvent.ExecutionReport.Filled(
                        clOrdId = e.clOrdId,
                        orderId = e.orderId,
                        execId = e.execId,
                        fillQty = e.fillQty,
                        fillPrice = e.fillPrice,
                        cumQty = e.cumQty,
                        timestamp = e.timestamp,
                    )
                } else {
                    OrderStateEvent.ExecutionReport.PartialFill(
                        clOrdId = e.clOrdId,
                        orderId = e.orderId,
                        execId = e.execId,
                        fillQty = e.fillQty,
                        fillPrice = e.fillPrice,
                        cumQty = e.cumQty,
                        leavesQty = e.leavesQty,
                        timestamp = e.timestamp,
                    )
                }
                applyOrderEvent(orderEvent)
                positionTracker.onFill(e.side, e.fillQty, e.fillPrice)
                logger.info {
                    "Fill: ${e.fillQty} @ ${e.fillPrice}, position=${positionTracker.getPosition()}, " +
                        "pnl=${positionTracker.getRealizedPnl()}"
                }
            }
            is Event.OrderCanceled -> {
                val orderEvent = OrderStateEvent.ExecutionReport.Canceled(
                    clOrdId = e.clOrdId,
                    orderId = e.orderId,
                    reason = e.reason,
                    timestamp = e.timestamp,
                )
                applyOrderEvent(orderEvent)
                logger.info { "Order canceled: ${e.orderId} - ${e.reason}" }
            }
            is Event.OrderAmended -> {
                val orderEvent = OrderStateEvent.ExecutionReport.Amended(
                    clOrdId = e.clOrdId,
                    orderId = e.newOrderId,
                    previousOrderId = e.oldOrderId,
                    newPrice = e.newPrice,
                    newQty = e.newQty,
                    timestamp = e.timestamp,
                )
                applyOrderEvent(orderEvent)
                logger.info { "Order amended: ${e.oldOrderId} -> ${e.newOrderId} @ ${e.newPrice}" }
            }
            is Event.OrderRejected -> {
                throw BotFatalException("Order rejected: ${e.clOrdId} - ${e.reason}")
            }
            else -> {}
        }
    }

    private fun applyOrderEvent(orderEvent: OrderStateEvent.ExecutionReport) {
        when (val result = orderStore.apply(orderEvent)) {
            is ApplyResult.Success -> {}
            is ApplyResult.SuccessWithWarning -> {
                logger.warn { "Order event warning: ${result.warning}" }
            }
            is ApplyResult.OrderNotFound -> {
                logger.warn { "Order not found: ${result.clOrdId}" }
            }
            is ApplyResult.InvalidTransition -> {
                logger.error { "Invalid order transition: ${result.reason}" }
            }
            is ApplyResult.DuplicateExecution -> {
                logger.debug { "Duplicate execution: ${result.execId}" }
            }
        }
    }

    private fun checkMaxLoss() {
        val pnl = positionTracker.getRealizedPnl()
        if (pnl < maxLossUsd.negate()) {
            throw BotFatalException("Max loss exceeded: PnL=$pnl, limit=-$maxLossUsd")
        }
    }

    private fun processBookEvents(event: MutableEvent) {
        val bookSnapshot = event.orderBookSnapshot ?: return

        when (event.event) {
            is Event.BookSnapshot, is Event.BookUpdate -> {
                val now = System.currentTimeMillis()
                if (now - lastQuoteTime.get() < requoteIntervalMs) {
                    return
                }

                if (!bookSnapshot.isValid()) {
                    return
                }

                val position = positionTracker.getPosition()
                val activeOrders = orderStore.activeOrders()

                val actions = try {
                    strategy.onBookSnapshot(bookSnapshot, position, activeOrders)
                } catch (e: Exception) {
                    throw BotFatalException("Strategy exception: ${e.message}", e)
                }

                if (actions.isNotEmpty()) {
                    event.commands = processStrategyActions(actions, position)
                    lastQuoteTime.set(now)
                    logger.debug { "Strategy generated ${actions.size} actions at ${bookSnapshot.midPrice}" }
                }
            }
            else -> {}
        }
    }

    private fun processStrategyActions(actions: List<StrategyAction>, currentPosition: BigDecimal): List<Command> {
        val commands = mutableListOf<Command>()

        for (action in actions) {
            when (action) {
                is StrategyAction.Place -> {
                    val intent = action.intent
                    when (val result = riskChecker.check(intent, currentPosition, orderStore.size())) {
                        is RiskCheckResult.Approved -> {
                            val clOrdId = generateClOrdId()
                            val createInstruction = OrderStateEvent.Instruction.Create(
                                clOrdId = clOrdId,
                                side = intent.side,
                                price = intent.price,
                                qty = intent.qty,
                                timestamp = System.currentTimeMillis(),
                            )
                            when (val createResult = orderStore.create(createInstruction)) {
                                is CreateResult.Created -> {
                                    commands.add(
                                        Command.PlaceOrder(
                                            clOrdId = clOrdId,
                                            symbol = symbol,
                                            side = intent.side,
                                            price = intent.price,
                                            qty = intent.qty,
                                            postOnly = intent.postOnly,
                                        ),
                                    )
                                    logger.info { "Place: ${intent.side} ${intent.qty} @ ${intent.price}" }
                                }
                                is CreateResult.DuplicateClOrdId -> {
                                    logger.error { "Duplicate clOrdId: $clOrdId" }
                                }
                            }
                        }
                        is RiskCheckResult.Rejected -> {
                            throw BotFatalException("RISK BREACH: ${result.reason}")
                        }
                    }
                }
                is StrategyAction.Amend -> {
                    commands.add(
                        Command.AmendOrder(
                            clOrdId = action.clOrdId,
                            newPrice = action.newPrice,
                            newQty = action.newQty,
                        ),
                    )
                    logger.info { "Amend: ${action.clOrdId} -> ${action.newPrice}" }
                }
                is StrategyAction.Cancel -> {
                    commands.add(Command.CancelOrder(clOrdId = action.clOrdId))
                    logger.info { "Cancel: ${action.clOrdId}" }
                }
            }
        }

        return commands
    }

    private fun generateClOrdId(): String =
        UUID.randomUUID().toString().replace("-", "").take(18)

    public fun getActiveOrders(): List<OrderSnapshot> = orderStore.activeOrders()

    public fun getPosition(): BigDecimal = positionTracker.getPosition()

    public fun getRealizedPnl(): BigDecimal = positionTracker.getRealizedPnl()
}
