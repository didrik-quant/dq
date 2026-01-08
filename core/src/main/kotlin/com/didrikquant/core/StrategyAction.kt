package com.didrikquant.core

import java.math.BigDecimal

/**
 * Actions that a strategy can emit to manage its orders.
 *
 * The strategy receives its current open orders and decides what to do:
 * - Place new orders where needed
 * - Amend existing orders when price has drifted (preserves queue priority)
 * - Cancel orders that are no longer wanted
 */
public sealed class StrategyAction {
    public data class Place(
        val intent: OrderIntent,
    ) : StrategyAction()

    public data class Amend(
        val clOrdId: String,
        val newPrice: BigDecimal,
        val newQty: BigDecimal? = null,
    ) : StrategyAction()

    public data class Cancel(
        val clOrdId: String,
    ) : StrategyAction()
}
