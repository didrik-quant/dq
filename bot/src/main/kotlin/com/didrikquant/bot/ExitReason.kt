package com.didrikquant.bot

/**
 * Exit codes for the bot process.
 * Non-zero codes indicate abnormal termination that the harness should treat as a failure.
 */
public enum class ExitReason(public val code: Int) {
    /** Normal completion - trade target reached or max duration elapsed */
    SUCCESS(0),

    /** Kill switch triggered (strategy exception, disruptor error, or max loss) */
    KILL_SWITCH(1),

    /** Public WebSocket disconnected unexpectedly */
    PUBLIC_WS_DISCONNECT(2),

    /** Private WebSocket disconnected unexpectedly */
    PRIVATE_WS_DISCONNECT(3),
}
