package com.didrikquant.core

import com.didrikquant.orderstate.Side
import java.math.BigDecimal

public data class OrderIntent(
    val side: Side,
    val price: BigDecimal,
    val qty: BigDecimal,
    val postOnly: Boolean = true,
)
