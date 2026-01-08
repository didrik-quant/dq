package com.didrikquant.core

import java.math.BigDecimal

public data class PendingOrderIntent(
    val clOrdId: String,
    val side: Side,
    val price: BigDecimal,
    val qty: BigDecimal,
)
