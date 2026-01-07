package com.didrikquant.strategy

import com.didrikquant.core.Side
import java.math.BigDecimal

public data class OrderIntent(
    val side: Side,
    val price: BigDecimal,
    val qty: BigDecimal,
    val postOnly: Boolean = true,
)
