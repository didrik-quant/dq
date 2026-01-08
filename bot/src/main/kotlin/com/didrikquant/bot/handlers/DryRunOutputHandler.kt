package com.didrikquant.bot.handlers

import com.didrikquant.core.Command
import com.didrikquant.core.disruptor.MutableEvent
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

public class DryRunOutputHandler : CommandOutputHandler {
    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        val commands = event.commands
        if (commands.isEmpty()) return

        val bookSnapshot = event.orderBookSnapshot
        val mid = bookSnapshot?.midPrice
        val spread = bookSnapshot?.spreadBps
        val topBid = bookSnapshot?.bids?.firstOrNull()
        val topAsk = bookSnapshot?.asks?.firstOrNull()

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
                    logger.info { "[DRY-RUN] Would cancel: ${cmd.clOrdId}" }
                }
                is Command.CancelAll -> {
                    logger.info { "[DRY-RUN] Would cancel all orders for ${cmd.symbol ?: "all symbols"}" }
                }
                is Command.AmendOrder -> {
                    logger.info { "[DRY-RUN] Would amend: ${cmd.clOrdId} -> ${cmd.newPrice}" }
                }
            }
        }
    }
}
