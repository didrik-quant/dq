package com.didrikquant.orderstate

import java.math.BigDecimal
import java.math.RoundingMode

public data class OrderSnapshot(
    val clOrdId: String,
    val orderId: String,
    val side: Side,
    val originalPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val originalQty: BigDecimal,
    val currentQty: BigDecimal,
    val filledQty: BigDecimal,
    val avgFillPrice: BigDecimal?,
    val state: OrderState,
    val lastUpdateTimestamp: Long,
    val appliedExecIds: Set<String> = emptySet(),
) {
    val remainingQty: BigDecimal
        get() = currentQty - filledQty

    val hasFills: Boolean
        get() = filledQty > BigDecimal.ZERO

    val isTerminal: Boolean
        get() = state.isTerminal()

    val isActive: Boolean
        get() = state.isActive()

    public fun apply(event: OrderStateEvent): TransitionResult {
        if (event.clOrdId != clOrdId) {
            return TransitionResult.Invalid(
                "Event clOrdId '${event.clOrdId}' doesn't match order '$clOrdId'",
            )
        }

        if (state.isTerminal()) {
            return TransitionResult.Invalid("Order is in terminal state $state, cannot apply $event")
        }

        return when (event) {
            is OrderStateEvent.ExecutionReport.PendingNew -> applyPendingNew(event)
            is OrderStateEvent.ExecutionReport.Accepted -> applyAccepted(event)
            is OrderStateEvent.ExecutionReport.Trade -> applyTrade(event)
            is OrderStateEvent.ExecutionReport.Canceled -> applyCanceled(event)
            is OrderStateEvent.ExecutionReport.Rejected -> applyRejected(event)
            is OrderStateEvent.ExecutionReport.Expired -> applyExpired(event)
            is OrderStateEvent.ExecutionReport.Amended -> applyAmended(event)
            is OrderStateEvent.ExecutionReport.Restated -> applyRestated(event)
            is OrderStateEvent.Instruction -> TransitionResult.Unchanged(this)
        }
    }

    private fun applyPendingNew(event: OrderStateEvent.ExecutionReport.PendingNew): TransitionResult {
        if (state != OrderState.PENDING_NEW) {
            return TransitionResult.Invalid("Cannot apply PendingNew in state $state")
        }
        return TransitionResult.Success(
            copy(
                orderId = event.orderId,
                lastUpdateTimestamp = event.timestamp,
            ),
        )
    }

    private fun applyAccepted(event: OrderStateEvent.ExecutionReport.Accepted): TransitionResult {
        if (state != OrderState.PENDING_NEW) {
            return TransitionResult.Invalid("Cannot apply Accepted in state $state")
        }
        return TransitionResult.Success(
            copy(
                orderId = event.orderId,
                state = OrderState.OPEN,
                lastUpdateTimestamp = event.timestamp,
            ),
        )
    }

    private fun applyTrade(event: OrderStateEvent.ExecutionReport.Trade): TransitionResult {
        if (state !in OrderState.FILLABLE_STATES) {
            return TransitionResult.Invalid("Cannot apply Trade in state $state")
        }

        if (event.execId in appliedExecIds) {
            return TransitionResult.Duplicate(event.execId)
        }

        val newFilledQty = filledQty + event.fillQty
        val newRemainingQty = currentQty - newFilledQty
        val newState = if (newRemainingQty <= BigDecimal.ZERO) OrderState.FILLED else OrderState.PARTIALLY_FILLED

        return TransitionResult.Success(
            copy(
                orderId = if (orderId.isEmpty()) event.orderId else orderId,
                filledQty = newFilledQty,
                avgFillPrice = calculateNewAvgPrice(event.fillQty, event.fillPrice),
                state = newState,
                lastUpdateTimestamp = event.timestamp,
                appliedExecIds = appliedExecIds + event.execId,
            ),
        )
    }

    private fun applyCanceled(event: OrderStateEvent.ExecutionReport.Canceled): TransitionResult {
        if (state !in OrderState.CANCELABLE_STATES) {
            return TransitionResult.Invalid("Cannot apply Canceled in state $state")
        }
        return TransitionResult.Success(
            copy(
                orderId = event.orderId.ifEmpty { orderId },
                state = OrderState.CANCELED,
                lastUpdateTimestamp = event.timestamp,
            ),
        )
    }

    private fun applyRejected(event: OrderStateEvent.ExecutionReport.Rejected): TransitionResult {
        if (state != OrderState.PENDING_NEW) {
            return TransitionResult.Invalid("Cannot apply Rejected in state $state")
        }
        return TransitionResult.Success(
            copy(
                orderId = event.orderId.ifEmpty { orderId },
                state = OrderState.REJECTED,
                lastUpdateTimestamp = event.timestamp,
            ),
        )
    }

    private fun applyExpired(event: OrderStateEvent.ExecutionReport.Expired): TransitionResult {
        if (state !in OrderState.ACTIVE_STATES) {
            return TransitionResult.Invalid("Cannot apply Expired in state $state")
        }
        return TransitionResult.Success(
            copy(
                state = OrderState.EXPIRED,
                lastUpdateTimestamp = event.timestamp,
            ),
        )
    }

    private fun applyAmended(event: OrderStateEvent.ExecutionReport.Amended): TransitionResult {
        if (state !in OrderState.AMENDABLE_STATES) {
            return TransitionResult.Invalid("Cannot apply Amended in state $state")
        }
        return TransitionResult.Success(
            copy(
                orderId = event.orderId,
                currentPrice = event.newPrice ?: currentPrice,
                currentQty = event.newQty ?: currentQty,
                lastUpdateTimestamp = event.timestamp,
            ),
        )
    }

    private fun applyRestated(event: OrderStateEvent.ExecutionReport.Restated): TransitionResult {
        if (state !in OrderState.ACTIVE_STATES) {
            return TransitionResult.Invalid("Cannot apply Restated in state $state")
        }
        return TransitionResult.Success(
            copy(
                orderId = event.orderId,
                currentPrice = event.newPrice ?: currentPrice,
                currentQty = event.newQty ?: currentQty,
                lastUpdateTimestamp = event.timestamp,
            ),
        )
    }

    private fun calculateNewAvgPrice(fillQty: BigDecimal, fillPrice: BigDecimal): BigDecimal {
        val prevAvg = avgFillPrice ?: return fillPrice
        val newFilledQty = filledQty + fillQty

        if (newFilledQty == BigDecimal.ZERO) return fillPrice

        return ((prevAvg * filledQty) + (fillPrice * fillQty))
            .divide(newFilledQty, 8, RoundingMode.HALF_UP)
    }

    public companion object {
        public fun fromInstruction(instruction: OrderStateEvent.Instruction.Create): OrderSnapshot {
            return OrderSnapshot(
                clOrdId = instruction.clOrdId,
                orderId = "",
                side = instruction.side,
                originalPrice = instruction.price,
                currentPrice = instruction.price,
                originalQty = instruction.qty,
                currentQty = instruction.qty,
                filledQty = BigDecimal.ZERO,
                avgFillPrice = null,
                state = OrderState.PENDING_NEW,
                lastUpdateTimestamp = instruction.timestamp,
                appliedExecIds = emptySet(),
            )
        }
    }
}

public sealed class TransitionResult {
    public data class Success(val snapshot: OrderSnapshot) : TransitionResult()

    public data class SuccessWithWarning(
        val snapshot: OrderSnapshot,
        val warning: String,
    ) : TransitionResult()

    public data class Unchanged(val snapshot: OrderSnapshot) : TransitionResult()

    public data class Duplicate(val execId: String) : TransitionResult()

    public data class Invalid(val reason: String) : TransitionResult()

    public fun snapshotOrNull(): OrderSnapshot? = when (this) {
        is Success -> snapshot
        is SuccessWithWarning -> snapshot
        is Unchanged -> snapshot
        is Duplicate -> null
        is Invalid -> null
    }

    public fun isOk(): Boolean = when (this) {
        is Success -> true
        is SuccessWithWarning -> true
        is Unchanged -> true
        is Duplicate -> false
        is Invalid -> false
    }
}
