package com.didrikquant.core

import java.math.BigDecimal
import java.util.TreeMap

public data class PriceLevel(
    val price: BigDecimal,
    val qty: BigDecimal,
)

public class OrderBook(public val symbol: String) {
    private val bids: TreeMap<BigDecimal, BigDecimal> = TreeMap(reverseOrder())
    private val asks: TreeMap<BigDecimal, BigDecimal> = TreeMap()

    public var sequence: Long = 0L
        private set

    public val bestBid: BigDecimal?
        get() = bids.firstEntry()?.key

    public val bestAsk: BigDecimal?
        get() = asks.firstEntry()?.key

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

    public fun applySnapshot(
        bidLevels: List<PriceLevel>,
        askLevels: List<PriceLevel>,
        seq: Long,
    ) {
        bids.clear()
        asks.clear()
        bidLevels.forEach { bids[it.price] = it.qty }
        askLevels.forEach { asks[it.price] = it.qty }
        sequence = seq
    }

    public fun applyUpdate(
        bidUpdates: List<PriceLevel>,
        askUpdates: List<PriceLevel>,
        seq: Long,
    ) {
        bidUpdates.forEach { level ->
            if (level.qty == ZERO) {
                bids.remove(level.price)
            } else {
                bids[level.price] = level.qty
            }
        }
        askUpdates.forEach { level ->
            if (level.qty == ZERO) {
                asks.remove(level.price)
            } else {
                asks[level.price] = level.qty
            }
        }
        sequence = seq
    }

    public fun topBids(n: Int): List<PriceLevel> =
        bids.entries.take(n).map { PriceLevel(it.key, it.value) }

    public fun topAsks(n: Int): List<PriceLevel> =
        asks.entries.take(n).map { PriceLevel(it.key, it.value) }

    public fun isValid(): Boolean = bids.isNotEmpty() && asks.isNotEmpty()

    public fun snapshot(depth: Int = 10): OrderBookSnapshot = OrderBookSnapshot(
        symbol = symbol,
        sequence = sequence,
        bids = topBids(depth),
        asks = topAsks(depth),
    )

    override fun toString(): String =
        "OrderBook($symbol, bid=$bestBid, ask=$bestAsk, mid=$midPrice, spread=${spreadBps}bps)"
}
