package com.didrikquant.bot.handlers

import com.didrikquant.core.Command
import com.didrikquant.core.OrderBook
import com.didrikquant.core.disruptor.MutableEvent
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

public class DryRunOutputHandler(
    private val orderBook: OrderBook,
) : CommandOutputHandler {
    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        val commands = event.commands
        if (commands.isEmpty()) return

        val mid = orderBook.midPrice
        val spread = orderBook.spreadBps
        val topBid = orderBook.topBids(1).firstOrNull()
        val topAsk = orderBook.topAsks(1).firstOrNull()

        logger.info {
            "Book: mid=$mid, spread=${spread}bps, " +
                "bid=${topBid?.price} x ${topBid?.qty}, " +
                "ask=${topAsk?.price} x ${topAsk?.qty}"
        }

        commands.forEach { cmd ->
            when (cmd) {
                is Command.PlaceOrder -> {
                    val distanceFromMid =
                        mid?.let { midPrice ->
                            (cmd.price - midPrice)
                                .abs()
                                .divide(midPrice, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal(10000))
                                .toInt()
                        } ?: 0
                    logger.info {
                        "[DRY-RUN] Would place: ${cmd.side} ${cmd.qty} @ ${cmd.price} " +
                            "(${distanceFromMid}bps from mid, postOnly=${cmd.postOnly})"
                    }
                }
                is Command.CancelOrder -> {
                    logger.info { "[DRY-RUN] Would cancel: ${cmd.orderId}" }
                }
                is Command.CancelAll -> {
                    logger.info { "[DRY-RUN] Would cancel all orders for ${cmd.symbol ?: "all symbols"}" }
                }
                is Command.AmendOrder -> {
                    logger.info { "[DRY-RUN] Would amend: ${cmd.orderId}" }
                }
            }
        }
    }

    override fun drainCommands(): List<Command> = emptyList()
}
