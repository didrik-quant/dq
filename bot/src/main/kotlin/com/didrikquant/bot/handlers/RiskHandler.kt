package com.didrikquant.bot.handlers

import com.didrikquant.core.Command
import com.didrikquant.core.StrategyAction
import com.didrikquant.core.disruptor.MutableEvent
import com.didrikquant.execution.OrderManager
import com.didrikquant.risk.RiskCheckResult
import com.didrikquant.risk.RiskChecker
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

public class RiskHandler(
    private val riskChecker: RiskChecker,
    private val orderManager: OrderManager,
    private val symbol: String,
) : EventHandler<MutableEvent> {
    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        val actions = event.actions
        if (actions.isEmpty()) return

        val commands = mutableListOf<Command>()
        val position = orderManager.getPosition()
        val openOrderCount = orderManager.getOpenOrderCount()

        for (action in actions) {
            when (action) {
                is StrategyAction.Place -> {
                    val intent = action.intent
                    when (val result = riskChecker.check(intent, position, openOrderCount)) {
                        is RiskCheckResult.Approved -> {
                            val clOrdId = orderManager.generateClOrdId()
                            orderManager.registerPendingOrder(clOrdId, intent.side, intent.price, intent.qty)
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
                        is RiskCheckResult.Rejected -> {
                            logger.warn { "Risk rejected: ${result.reason}" }
                        }
                    }
                }
                is StrategyAction.Amend -> {
                    commands.add(
                        Command.AmendOrder(
                            orderId = action.orderId,
                            newPrice = action.newPrice,
                            newQty = action.newQty,
                        ),
                    )
                    logger.info { "Amend: ${action.orderId} -> ${action.newPrice}" }
                }
                is StrategyAction.Cancel -> {
                    commands.add(Command.CancelOrder(orderId = action.orderId))
                    logger.info { "Cancel: ${action.orderId}" }
                }
            }
        }

        if (commands.isNotEmpty()) {
            event.commands = commands
        }
    }
}
