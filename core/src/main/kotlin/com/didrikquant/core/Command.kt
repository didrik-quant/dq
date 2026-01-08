package com.didrikquant.core

import java.math.BigDecimal

public sealed class Command {

    public data class PlaceOrder(
        val clOrdId: String,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val qty: BigDecimal,
        val postOnly: Boolean = true,
        val timeInForce: TimeInForce = TimeInForce.GTC,
    ) : Command()

    public data class CancelOrder(
        val orderId: String,
    ) : Command()

    public data class AmendOrder(
        val orderId: String,
        val newPrice: BigDecimal,
        val newQty: BigDecimal? = null,
    ) : Command()

    public data class CancelAll(
        val symbol: String? = null,
    ) : Command()
}

public enum class TimeInForce {
    GTC,
    GTD,
    IOC,
}
