package com.didrikquant.core

import java.math.BigDecimal

public data class OrderBookSnapshot(
    val symbol: String,
    val sequence: Long,
    val bids: List<PriceLevel>,
    val asks: List<PriceLevel>,
) {
    public val bestBid: BigDecimal?
        get() = bids.firstOrNull()?.price

    public val bestAsk: BigDecimal?
        get() = asks.firstOrNull()?.price

    public val midPrice: BigDecimal?
        get() {
            val bid = bestBid ?: return null
            val ask = bestAsk ?: return null
            return (bid + ask).safeDivide(TWO, PRICE_SCALE)
        }

    public val spread: BigDecimal?
        get() {
            val bid = bestBid ?: return null
            val ask = bestAsk ?: return null
            return ask - bid
        }

    public val spreadBps: BigDecimal?
        get() {
            val mid = midPrice ?: return null
            val sp = spread ?: return null
            return sp.safeDivide(mid, 8) * TEN_THOUSAND
        }

    public fun isValid(): Boolean = bids.isNotEmpty() && asks.isNotEmpty()

    public fun topBids(n: Int): List<PriceLevel> = bids.take(n)

    public fun topAsks(n: Int): List<PriceLevel> = asks.take(n)
}
