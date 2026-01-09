package com.didrikquant.risk

import com.didrikquant.core.OrderIntent
import com.didrikquant.orderstate.Side
import com.didrikquant.core.absoluteValue
import java.math.BigDecimal

public class RiskChecker(private val config: RiskConfig) {

    public fun check(
        intent: OrderIntent,
        currentPosition: BigDecimal,
        openOrderCount: Int,
    ): RiskCheckResult {
        if (intent.qty < config.minOrderSize) {
            return RiskCheckResult.Rejected("Order size ${intent.qty} below minimum ${config.minOrderSize}")
        }

        if (intent.qty > config.maxOrderSize) {
            return RiskCheckResult.Rejected("Order size ${intent.qty} exceeds max ${config.maxOrderSize}")
        }

        if (openOrderCount >= config.maxOpenOrders) {
            return RiskCheckResult.Rejected("Max open orders reached: $openOrderCount")
        }

        val projectedPosition = when (intent.side) {
            Side.BUY -> currentPosition + intent.qty
            Side.SELL -> currentPosition - intent.qty
        }

        if (projectedPosition.absoluteValue() > config.maxPositionSize) {
            return RiskCheckResult.Rejected(
                "Projected position ${projectedPosition.absoluteValue()} exceeds max ${config.maxPositionSize}",
            )
        }

        return RiskCheckResult.Approved
    }
}

public sealed class RiskCheckResult {
    public object Approved : RiskCheckResult()
    public data class Rejected(val reason: String) : RiskCheckResult()
}
