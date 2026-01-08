package com.didrikquant.core

import java.math.BigDecimal

public data class TrackedOrder(
    val orderId: String,
    val clOrdId: String,
    val symbol: String,
    val side: Side,
    val price: BigDecimal,
    val originalQty: BigDecimal,
    val filledQty: BigDecimal = BigDecimal.ZERO,
    val status: OrderStatus = OrderStatus.PENDING,
) {
    /** Returns the remaining quantity that hasn't been filled yet */
    public fun remainingQty(): BigDecimal = originalQty - filledQty

    /** Returns true if this order has any fills */
    public fun isPartiallyFilled(): Boolean = filledQty > BigDecimal.ZERO
}

public enum class OrderStatus {
    PENDING,
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
}
