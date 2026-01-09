package com.didrikquant.orderstate

/**
 * Order side - duplicated from core module to keep order-state standalone.
 */
public enum class Side {
    BUY,
    SELL;

    /** Returns the opposite side */
    public fun opposite(): Side = when (this) {
        BUY -> SELL
        SELL -> BUY
    }
}
