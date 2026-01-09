package com.didrikquant.orderstate

/**
 * Reasons for exchange-initiated order restatement.
 *
 * Restatement occurs when the exchange modifies an order without user request.
 */
public enum class RestateReason {
    /** Margin/position maintenance adjustment */
    MARGIN_MAINTENANCE,

    /** Reduce non-tradable liquidity */
    LIQUIDITY_REDUCTION,

    /** Corporate action adjustment */
    CORPORATE_ACTION,

    /** Other/unknown reason */
    OTHER,
}
