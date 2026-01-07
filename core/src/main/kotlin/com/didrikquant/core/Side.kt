package com.didrikquant.core

public enum class Side {
    BUY,
    SELL;

    public fun opposite(): Side = when (this) {
        BUY -> SELL
        SELL -> BUY
    }
}
