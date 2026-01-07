package com.didrikquant.bot.handlers

import com.didrikquant.core.Command
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
    private val strategyHandler: StrategyHandler,
    private val symbol: String,
) : EventHandler<MutableEvent> {
    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        val intents = strategyHandler.consumeIntents()
        if (intents.isEmpty()) return

        val approvedCommands = mutableListOf<Command>()
        val position = orderManager.getPosition()
        val openOrders = orderManager.getOpenOrderCount()

        for (intent in intents) {
            when (val result = riskChecker.check(intent, position, openOrders)) {
                is RiskCheckResult.Approved -> {
                    val clOrdId = orderManager.generateClOrdId()
                    orderManager.registerPendingOrder(clOrdId, intent.side, intent.price, intent.qty)

                    approvedCommands.add(
                        Command.PlaceOrder(
                            clOrdId = clOrdId,
                            symbol = symbol,
                            side = intent.side,
                            price = intent.price,
                            qty = intent.qty,
                            postOnly = intent.postOnly,
                        ),
                    )
                    logger.info { "Risk approved: ${intent.side} ${intent.qty} @ ${intent.price}" }
                }
                is RiskCheckResult.Rejected -> {
                    logger.warn { "Risk rejected: ${result.reason}" }
                }
            }
        }

        if (approvedCommands.isNotEmpty()) {
            event.commands = approvedCommands
        }
    }
}
