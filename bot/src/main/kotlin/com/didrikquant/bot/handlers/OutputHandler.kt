package com.didrikquant.bot.handlers

import com.didrikquant.core.Command
import com.didrikquant.core.disruptor.MutableEvent
import com.lmax.disruptor.EventHandler
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

private val logger = KotlinLogging.logger {}

public interface CommandOutputHandler : EventHandler<MutableEvent> {
    public fun drainCommands(): List<Command>
}

public class OutputHandler : CommandOutputHandler {
    private val pendingCommands = ConcurrentLinkedQueue<Command>()

    override fun onEvent(
        event: MutableEvent,
        sequence: Long,
        endOfBatch: Boolean,
    ) {
        val commands = event.commands
        if (commands.isNotEmpty()) {
            commands.forEach { cmd ->
                pendingCommands.offer(cmd)
                logger.debug { "Queued command: $cmd" }
            }
        }
    }

    override fun drainCommands(): List<Command> {
        val commands = mutableListOf<Command>()
        while (true) {
            val cmd = pendingCommands.poll() ?: break
            commands.add(cmd)
        }
        return commands
    }
}
