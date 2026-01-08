package com.didrikquant.bot.handlers

import com.didrikquant.bot.CommandSender
import com.didrikquant.core.disruptor.MutableEvent
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

public interface CommandOutputHandler : EventHandler<MutableEvent>

public class OutputHandler(
    private val commandSender: CommandSender,
) : CommandOutputHandler {

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        val commands = event.commands
        if (commands.isEmpty()) return

        for (cmd in commands) {
            logger.debug { "Queuing command: $cmd" }
            commandSender.send(cmd)
        }
    }
}
