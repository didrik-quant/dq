package com.didrikquant.core

import java.math.BigDecimal
import java.math.RoundingMode

public val ZERO: BigDecimal = BigDecimal.ZERO
public val ONE: BigDecimal = BigDecimal.ONE
public val TWO: BigDecimal = BigDecimal("2")
public val HUNDRED: BigDecimal = BigDecimal("100")
public val TEN_THOUSAND: BigDecimal = BigDecimal("10000")

public const val PRICE_SCALE: Int = 5
public const val QTY_SCALE: Int = 8

public fun String.toBigDecimalOrNull(): BigDecimal? =
    try {
        BigDecimal(this)
    } catch (_: NumberFormatException) {
        null
    }

public fun BigDecimal.roundToTick(tickSize: BigDecimal = BigDecimal("0.00001")): BigDecimal =
    this.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize)

public fun Int.bpsToDecimal(): BigDecimal =
    BigDecimal(this).divide(TEN_THOUSAND, 8, RoundingMode.HALF_UP)

public fun BigDecimal.safeDivide(divisor: BigDecimal, scale: Int = 8): BigDecimal =
    if (divisor == ZERO) ZERO else this.divide(divisor, scale, RoundingMode.HALF_UP)

public fun BigDecimal.isPositive(): Boolean = this > ZERO

public fun BigDecimal.isNegative(): Boolean = this < ZERO

public fun BigDecimal.absoluteValue(): BigDecimal = this.abs()
