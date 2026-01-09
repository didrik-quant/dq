package com.didrikquant.orderstate

/**
 * Possible states an order can be in throughout its lifecycle.
 */
public enum class OrderState {
    /** Order creation requested, awaiting exchange response */
    PENDING_NEW,

    /** Order is live on the exchange book, no fills yet */
    OPEN,

    /** Order is live with partial fills */
    PARTIALLY_FILLED,

    /** Order fully filled (terminal) */
    FILLED,

    /** Order canceled (terminal) */
    CANCELED,

    /** Order rejected by exchange (terminal) */
    REJECTED,

    /** Order expired (terminal) */
    EXPIRED;

    /** Returns true if this is a terminal state (no further transitions possible) */
    public fun isTerminal(): Boolean = this in TERMINAL_STATES

    /** Returns true if this order is still active (can receive fills or be canceled) */
    public fun isActive(): Boolean = this in ACTIVE_STATES

    public companion object {
        /** States from which no further transitions are possible */
        public val TERMINAL_STATES: Set<OrderState> = setOf(FILLED, CANCELED, REJECTED, EXPIRED)

        /** States where the order is live and can receive fills */
        public val ACTIVE_STATES: Set<OrderState> = setOf(OPEN, PARTIALLY_FILLED)

        /** States where the order can receive fills (includes PENDING_NEW for race conditions where fill arrives before Accepted) */
        public val FILLABLE_STATES: Set<OrderState> = setOf(PENDING_NEW, OPEN, PARTIALLY_FILLED)

        /** States where the order can be canceled */
        public val CANCELABLE_STATES: Set<OrderState> = setOf(PENDING_NEW, OPEN, PARTIALLY_FILLED)

        /** States where the order can be amended */
        public val AMENDABLE_STATES: Set<OrderState> = setOf(OPEN, PARTIALLY_FILLED)
    }
}
