# Fill Handling Redesign

## Problem

1. **Hardcoded cumQty/leavesQty**: Kraken doesn't provide these values, so the code hardcodes `cumQty = fillQty` and `leavesQty = ZERO`, treating every fill as complete.

2. **Dual event system**: `Event.OrderFill` in core and `OrderStateEvent.ExecutionReport.PartialFill/Filled` in order-state require manual conversion in TradingHandler.

## Solution

Unify on `OrderStateEvent` as the single source of truth. OrderStore computes fill quantities internally.

## Changes

### 1. New Event Type: `ExecutionReport.Trade`

```kotlin
/** Trade execution (ExecType=Trade) - raw fill, quantities computed by OrderStore */
public data class Trade(
    override val clOrdId: String,
    override val orderId: String,
    val execId: String,
    val fillQty: BigDecimal,
    val fillPrice: BigDecimal,
    override val timestamp: Long,
) : ExecutionReport()
```

Replaces `PartialFill` and `Filled`. No cumQty/leavesQty - OrderStore computes these.

### 2. OrderSnapshot Changes

Rename `leavesQty` to `remainingQty`:

```kotlin
val remainingQty: BigDecimal
    get() = currentQty - filledQty
```

New `applyTrade()` computes quantities:

```kotlin
private fun applyTrade(event: ExecutionReport.Trade): TransitionResult {
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
            filledQty = newFilledQty,
            avgFillPrice = calculateNewAvgPrice(event.fillQty, event.fillPrice),
            state = newState,
            lastUpdateTimestamp = event.timestamp,
            appliedExecIds = appliedExecIds + event.execId,
        )
    )
}
```

### 3. Simplify core/Event.kt

Remove order-related events:

```kotlin
public sealed class Event {
    data class BookSnapshot(...) : Event()
    data class BookUpdate(...) : Event()
    data class Connected(...) : Event()
    data class Disconnected(...) : Event()
    data class Heartbeat(...) : Event()
    object Shutdown : Event()
}
```

Removed: `OrderAccepted`, `OrderFill`, `OrderCanceled`, `OrderAmended`, `OrderRejected`

### 4. MutableEvent Changes

Add field for order events:

```kotlin
class MutableEvent {
    var event: Event? = null
    var orderEvent: OrderStateEvent? = null
    // ... rest
}
```

### 5. Kraken Client Changes

Publish `OrderStateEvent.ExecutionReport.*` directly:

```kotlin
private fun processFills(obj: JsonObject) {
    // ...
    publishOrderEvent(
        OrderStateEvent.ExecutionReport.Trade(
            clOrdId = cliOrdId,
            orderId = orderId,
            execId = fillId,
            fillQty = BigDecimal.valueOf(qty),
            fillPrice = BigDecimal.valueOf(price),
            timestamp = time,
        )
    )
}
```

Similarly for `Accepted`, `Canceled`, `Amended`, `Rejected`.

### 6. Fail-Fast TradingHandler

Process `orderEvent` directly, fail-fast on errors:

```kotlin
private fun processOrderEvents(event: MutableEvent) {
    val orderEvent = event.orderEvent as? OrderStateEvent.ExecutionReport ?: return

    when (val result = orderStore.apply(orderEvent)) {
        is ApplyResult.Success -> handleSuccess(orderEvent)
        is ApplyResult.SuccessWithWarning -> {
            logger.warn { "Order event warning: ${result.warning}" }
            handleSuccess(orderEvent)
        }
        is ApplyResult.DuplicateExecution -> {
            logger.debug { "Duplicate execution: ${result.execId}" }
        }
        is ApplyResult.OrderNotFound -> {
            throw BotFatalException("Order not found: ${result.clOrdId}")
        }
        is ApplyResult.InvalidTransition -> {
            throw BotFatalException("Invalid order transition: ${result.reason}")
        }
    }
}
```

## Files to Modify

1. `order-state/src/main/kotlin/.../OrderStateEvent.kt` - Add Trade, remove PartialFill/Filled
2. `order-state/src/main/kotlin/.../OrderSnapshot.kt` - Rename leavesQty, add applyTrade
3. `core/src/main/kotlin/.../Event.kt` - Remove order events
4. `core/src/main/kotlin/.../disruptor/MutableEvent.kt` - Add orderEvent field
5. `kraken-client/src/main/kotlin/.../KrakenPrivateWs.kt` - Publish OrderStateEvent
6. `bot/src/main/kotlin/.../handlers/TradingHandler.kt` - Simplify, fail-fast
7. Tests - Update to new types
