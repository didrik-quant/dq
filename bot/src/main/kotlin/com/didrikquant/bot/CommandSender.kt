package com.didrikquant.bot

import com.didrikquant.core.Command
import com.didrikquant.kraken.KrakenRestClient
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

private val logger = KotlinLogging.logger {}

/**
 * Thread-safe command sender for use from Disruptor handlers.
 *
 * OutputHandler calls [send] from the Disruptor thread (non-coroutine context).
 * A separate coroutine polls [poll] and sends via WebSocket.
 * Exception handler calls [cancelAllAndShutdown] which blocks until cancel completes.
 */
public class CommandSender(
    private val restClient: KrakenRestClient?,
    private val symbol: String,
) {
    private val queue = ConcurrentLinkedQueue<Command>()

    @Volatile
    private var shutdown = false

    /**
     * Queue a command for sending. Called from Disruptor handlers.
     */
    public fun send(command: Command) {
        if (shutdown) {
            logger.warn { "Ignoring command after shutdown: $command" }
            return
        }
        queue.add(command)
    }

    /**
     * Poll for the next command. Called from the command-sending coroutine.
     */
    public fun poll(): Command? = queue.poll()

    /**
     * Check if there are pending commands.
     */
    public fun hasPending(): Boolean = queue.isNotEmpty()

    /**
     * Emergency shutdown: cancel all orders and mark as shut down.
     * Uses runBlocking since this is called from the exception handler.
     */
    public fun cancelAllAndShutdown() {
        if (shutdown) return
        shutdown = true
        queue.clear()

        if (restClient == null) {
            logger.info { "No REST client - skipping cancel (dry-run mode)" }
            return
        }

        logger.info { "Canceling all orders for $symbol..." }
        try {
            runBlocking {
                restClient.cancelAllOrders(symbol)
            }
            logger.info { "All orders canceled" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to cancel orders during shutdown" }
        }
    }

    public fun isShutdown(): Boolean = shutdown
}
