# Fill Handling Redesign Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Unify order events on `OrderStateEvent`, eliminating dual event system. OrderStore computes fill quantities internally.

**Architecture:** Kraken client publishes `OrderStateEvent.ExecutionReport.*` directly to ring buffer. OrderStore is single source of truth for order state. New `Trade` event replaces `PartialFill`/`Filled` - OrderStore computes `filledQty`/`remainingQty` from fill events.

**Tech Stack:** Kotlin 2.2.x, Bazel 8.5.0, Kotest for tests

---

### Task 1: Add `ExecutionReport.Trade` Event Type

**Files:**
- Modify: `order-state/src/main/kotlin/com/didrikquant/orderstate/OrderStateEvent.kt:73-94`

**Step 1: Write the failing test**

Create test file `order-state/src/test/kotlin/com/didrikquant/orderstate/TradeEventTests.kt`:

```kotlin
package com.didrikquant.orderstate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal

internal class TradeEventTests : FunSpec({

    test("Trade event transitions OPEN order to PARTIALLY_FILLED") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-001",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 1000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-001",
            orderId = "ex-001",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 1001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        val trade = OrderStateEvent.ExecutionReport.Trade(
            clOrdId = "order-001",
            orderId = "ex-001",
            execId = "exec-001",
            fillQty = BigDecimal("3.00"),
            fillPrice = BigDecimal("99.50"),
            timestamp = 1002L,
        )
        val result = snapshot.apply(trade)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (result as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.PARTIALLY_FILLED
        snapshot.filledQty shouldBe BigDecimal("3.00")
        snapshot.remainingQty shouldBe BigDecimal("7.00")
        snapshot.avgFillPrice shouldBe BigDecimal("99.50")
    }

    test("Trade event transitions to FILLED when remainingQty becomes zero") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-002",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 2000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-002",
            orderId = "ex-002",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 2001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        val trade = OrderStateEvent.ExecutionReport.Trade(
            clOrdId = "order-002",
            orderId = "ex-002",
            execId = "exec-002",
            fillQty = BigDecimal("5.00"),
            fillPrice = BigDecimal("100.00"),
            timestamp = 2002L,
        )
        val result = snapshot.apply(trade)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (result as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.FILLED
        snapshot.filledQty shouldBe BigDecimal("5.00")
        snapshot.remainingQty shouldBe BigDecimal.ZERO
        snapshot.isTerminal shouldBe true
    }

    test("Trade event can be applied to PENDING_NEW (race condition)") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-003",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 3000L,
        )
        val snapshot = OrderSnapshot.fromInstruction(create)

        val trade = OrderStateEvent.ExecutionReport.Trade(
            clOrdId = "order-003",
            orderId = "ex-003",
            execId = "exec-003",
            fillQty = BigDecimal("5.00"),
            fillPrice = BigDecimal("100.00"),
            timestamp = 3001L,
        )
        val result = snapshot.apply(trade)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        val filled = (result as TransitionResult.Success).snapshot

        filled.state shouldBe OrderState.FILLED
    }

    test("duplicate Trade execId is rejected") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-004",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 4000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-004",
            orderId = "ex-004",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 4001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        val trade1 = OrderStateEvent.ExecutionReport.Trade(
            clOrdId = "order-004",
            orderId = "ex-004",
            execId = "exec-same",
            fillQty = BigDecimal("3.00"),
            fillPrice = BigDecimal("100.00"),
            timestamp = 4002L,
        )
        snapshot = (snapshot.apply(trade1) as TransitionResult.Success).snapshot

        val trade2 = OrderStateEvent.ExecutionReport.Trade(
            clOrdId = "order-004",
            orderId = "ex-004",
            execId = "exec-same",
            fillQty = BigDecimal("3.00"),
            fillPrice = BigDecimal("100.00"),
            timestamp = 4003L,
        )
        val result = snapshot.apply(trade2)
        result.shouldBeInstanceOf<TransitionResult.Duplicate>()
        snapshot.filledQty shouldBe BigDecimal("3.00")
    }

    test("multiple Trade events compute weighted average price") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-005",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 5000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-005",
            orderId = "ex-005",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 5001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        // First fill: 4 @ 100
        val trade1 = OrderStateEvent.ExecutionReport.Trade(
            clOrdId = "order-005",
            orderId = "ex-005",
            execId = "exec-005a",
            fillQty = BigDecimal("4.00"),
            fillPrice = BigDecimal("100.00"),
            timestamp = 5002L,
        )
        snapshot = (snapshot.apply(trade1) as TransitionResult.Success).snapshot

        // Second fill: 6 @ 102
        // Weighted avg = (4*100 + 6*102) / 10 = 101.2
        val trade2 = OrderStateEvent.ExecutionReport.Trade(
            clOrdId = "order-005",
            orderId = "ex-005",
            execId = "exec-005b",
            fillQty = BigDecimal("6.00"),
            fillPrice = BigDecimal("102.00"),
            timestamp = 5003L,
        )
        snapshot = (snapshot.apply(trade2) as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.FILLED
        snapshot.avgFillPrice!!.compareTo(BigDecimal("101.20")) shouldBe 0
    }
})
```

**Step 2: Run test to verify it fails**

Run: `bazel test //order-state:order-state-test --test_filter=TradeEventTests`
Expected: FAIL with "Unresolved reference: Trade"

**Step 3: Add Trade event type to OrderStateEvent.kt**

Add after line 72 (after Accepted):

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

**Step 4: Run test to verify it still fails**

Run: `bazel test //order-state:order-state-test --test_filter=TradeEventTests`
Expected: FAIL with "Unresolved reference: remainingQty" or similar

**Step 5: Commit**

```bash
git add order-state/src/main/kotlin/com/didrikquant/orderstate/OrderStateEvent.kt
git add order-state/src/test/kotlin/com/didrikquant/orderstate/TradeEventTests.kt
git commit -m "Add ExecutionReport.Trade event type (tests failing)"
```

---

### Task 2: Rename `leavesQty` to `remainingQty` in OrderSnapshot

**Files:**
- Modify: `order-state/src/main/kotlin/com/didrikquant/orderstate/OrderSnapshot.kt:20-21`
- Modify: `order-state/src/test/kotlin/com/didrikquant/orderstate/OrderSnapshotExampleTests.kt` (multiple lines)

**Step 1: Rename leavesQty to remainingQty in OrderSnapshot.kt**

Change line 20-21 from:
```kotlin
    val leavesQty: BigDecimal
        get() = currentQty - filledQty
```

To:
```kotlin
    val remainingQty: BigDecimal
        get() = currentQty - filledQty
```

**Step 2: Update all references in OrderSnapshotExampleTests.kt**

Replace all occurrences of `leavesQty` with `remainingQty` in the test file. There are approximately 8 occurrences.

**Step 3: Run tests to verify rename works**

Run: `bazel test //order-state:order-state-test`
Expected: Tests pass (except TradeEventTests which still fail)

**Step 4: Commit**

```bash
git add order-state/
git commit -m "Rename leavesQty to remainingQty in OrderSnapshot"
```

---

### Task 3: Implement `applyTrade()` in OrderSnapshot

**Files:**
- Modify: `order-state/src/main/kotlin/com/didrikquant/orderstate/OrderSnapshot.kt:43-54` and new method

**Step 1: Add Trade case to apply() method**

In the `when (event)` block around line 43, add:

```kotlin
            is OrderStateEvent.ExecutionReport.Trade -> applyTrade(event)
```

**Step 2: Implement applyTrade() method**

Add new private method after `applyFilled()`:

```kotlin
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
```

**Step 3: Run tests to verify Trade tests pass**

Run: `bazel test //order-state:order-state-test`
Expected: All tests pass

**Step 4: Commit**

```bash
git add order-state/src/main/kotlin/com/didrikquant/orderstate/OrderSnapshot.kt
git commit -m "Implement applyTrade() in OrderSnapshot"
```

---

### Task 4: Remove PartialFill and Filled from OrderStateEvent

**Files:**
- Modify: `order-state/src/main/kotlin/com/didrikquant/orderstate/OrderStateEvent.kt:73-94`
- Modify: `order-state/src/main/kotlin/com/didrikquant/orderstate/OrderSnapshot.kt` (remove applyPartialFill, applyFilled)
- Modify: `order-state/src/test/kotlin/com/didrikquant/orderstate/OrderSnapshotExampleTests.kt` (update to use Trade)

**Step 1: Update tests to use Trade instead of PartialFill/Filled**

In `OrderSnapshotExampleTests.kt`, replace all `PartialFill` and `Filled` events with `Trade` events. Remove `cumQty` and `leavesQty` parameters.

Example transformation - change:
```kotlin
val partialFill = OrderStateEvent.ExecutionReport.PartialFill(
    clOrdId = "order-001",
    orderId = "exchange-order-123",
    execId = "exec-001",
    fillQty = BigDecimal("3.00"),
    fillPrice = BigDecimal("99.50"),
    cumQty = BigDecimal("3.00"),
    leavesQty = BigDecimal("7.00"),
    timestamp = 1002L,
)
```

To:
```kotlin
val trade = OrderStateEvent.ExecutionReport.Trade(
    clOrdId = "order-001",
    orderId = "exchange-order-123",
    execId = "exec-001",
    fillQty = BigDecimal("3.00"),
    fillPrice = BigDecimal("99.50"),
    timestamp = 1002L,
)
```

**Step 2: Remove PartialFill and Filled from OrderStateEvent.kt**

Delete lines 73-94 (the PartialFill and Filled data classes).

**Step 3: Remove applyPartialFill and applyFilled from OrderSnapshot.kt**

Delete the `applyPartialFill()` and `applyFilled()` methods (approximately lines 82-140).

Remove their cases from the `when` block in `apply()`.

**Step 4: Run tests**

Run: `bazel test //order-state:order-state-test`
Expected: All tests pass

**Step 5: Run lint**

Run: `bazel test //order-state:ktlint`
Expected: Pass

**Step 6: Commit**

```bash
git add order-state/
git commit -m "Remove PartialFill/Filled, use Trade exclusively"
```

---

### Task 5: Add `orderEvent` field to MutableEvent

**Files:**
- Modify: `core/src/main/kotlin/com/didrikquant/core/disruptor/MutableEvent.kt`
- Modify: `core/BUILD.bazel` (add order-state dependency)

**Step 1: Add order-state dependency to core/BUILD.bazel**

The core module already imports from order-state (Side enum), so dependency should exist. Verify and add if needed:

```bazel
deps = [
    "//order-state",
    ...
]
```

**Step 2: Add orderEvent field to MutableEvent.kt**

Add import:
```kotlin
import com.didrikquant.orderstate.OrderStateEvent
```

Add field after line 11:
```kotlin
    @Volatile
    public var orderEvent: OrderStateEvent? = null
```

Update `clear()` method to include:
```kotlin
        orderEvent = null
```

**Step 3: Run build**

Run: `bazel build //core`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add core/
git commit -m "Add orderEvent field to MutableEvent"
```

---

### Task 6: Update Kraken Client to Publish OrderStateEvent

**Files:**
- Modify: `kraken-client/src/main/kotlin/com/didrikquant/kraken/KrakenPrivateWs.kt`
- Modify: `kraken-client/BUILD.bazel` (add order-state dependency if needed)

**Step 1: Add order-state dependency to kraken-client/BUILD.bazel**

Add to deps:
```bazel
"//order-state",
```

**Step 2: Add import for OrderStateEvent**

Add at top of KrakenPrivateWs.kt:
```kotlin
import com.didrikquant.orderstate.OrderStateEvent
```

**Step 3: Add publishOrderEvent method**

Add after `publishEvent()` method (around line 410):
```kotlin
    private fun publishOrderEvent(orderEvent: OrderStateEvent) {
        val sequence = ringBuffer.next()
        try {
            val mutableEvent = ringBuffer[sequence]
            mutableEvent.clear()
            mutableEvent.orderEvent = orderEvent
        } finally {
            ringBuffer.publish(sequence)
        }
    }
```

**Step 4: Update processFills() to publish Trade**

Replace `processFills()` method (lines 327-357):

```kotlin
    private fun processFills(obj: JsonObject) {
        val fills = obj["fills"]?.jsonArray ?: return

        for (fill in fills) {
            val f = fill.jsonObject
            val orderId = f["order_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val cliOrdId = f["cli_ord_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val fillId = f["fill_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val price = f["price"]?.jsonPrimitive?.doubleOrNull ?: continue
            val qty = f["qty"]?.jsonPrimitive?.doubleOrNull ?: continue
            val isBuy = f["buy"]?.jsonPrimitive?.booleanOrNull ?: continue
            val time = f["time"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

            publishOrderEvent(
                OrderStateEvent.ExecutionReport.Trade(
                    clOrdId = cliOrdId,
                    orderId = orderId,
                    execId = fillId,
                    fillQty = BigDecimal.valueOf(qty),
                    fillPrice = BigDecimal.valueOf(price),
                    timestamp = time,
                ),
            )
            logger.info { "Fill: ${if (isBuy) "BUY" else "SELL"} $qty @ $price" }
        }
    }
```

**Step 5: Update processOpenOrders() to publish Accepted**

Replace the event publishing in `processOpenOrders()` (lines 372-383):

```kotlin
            publishOrderEvent(
                OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = cliOrdId,
                    orderId = orderId,
                    side = if (direction > 0) Side.BUY else Side.SELL,
                    price = BigDecimal.valueOf(limitPrice),
                    qty = BigDecimal.valueOf(qty),
                    timestamp = time,
                ),
            )
```

**Step 6: Update executeOrder() rejection to publish Rejected**

Replace the `publishEvent(Event.OrderRejected(...))` call (lines 232-238):

```kotlin
            publishOrderEvent(
                OrderStateEvent.ExecutionReport.Rejected.beforeAcceptance(
                    clOrdId = cmd.clOrdId,
                    reason = error,
                    timestamp = System.currentTimeMillis(),
                ),
            )
```

**Step 7: Update cancelOrder() to publish Canceled**

Replace the `publishEvent(Event.OrderCanceled(...))` call (lines 247-254):

```kotlin
            publishOrderEvent(
                OrderStateEvent.ExecutionReport.Canceled(
                    clOrdId = cmd.clOrdId,
                    orderId = "",
                    reason = "user_requested",
                    timestamp = System.currentTimeMillis(),
                ),
            )
```

**Step 8: Update amendOrder() to publish Amended**

Replace the `publishEvent(Event.OrderAmended(...))` call (lines 282-291):

```kotlin
            publishOrderEvent(
                OrderStateEvent.ExecutionReport.Amended(
                    clOrdId = cmd.clOrdId,
                    orderId = newOrderId,
                    previousOrderId = "",
                    newPrice = cmd.newPrice,
                    newQty = cmd.newQty,
                    timestamp = System.currentTimeMillis(),
                ),
            )
```

**Step 9: Run build**

Run: `bazel build //kraken-client`
Expected: Build succeeds

**Step 10: Run lint**

Run: `bazel test //kraken-client:ktlint`
Expected: Pass (fix any issues)

**Step 11: Commit**

```bash
git add kraken-client/
git commit -m "Update Kraken client to publish OrderStateEvent directly"
```

---

### Task 7: Simplify TradingHandler to Use orderEvent

**Files:**
- Modify: `bot/src/main/kotlin/com/didrikquant/bot/handlers/TradingHandler.kt`

**Step 1: Rewrite processOrderEvents() method**

Replace the entire `processOrderEvents()` method (lines 52-124):

```kotlin
    private fun processOrderEvents(event: MutableEvent) {
        val orderEvent = event.orderEvent as? OrderStateEvent.ExecutionReport ?: return

        when (val result = orderStore.apply(orderEvent)) {
            is ApplyResult.Success -> handleSuccessfulOrderEvent(orderEvent)
            is ApplyResult.SuccessWithWarning -> {
                logger.warn { "Order event warning: ${result.warning}" }
                handleSuccessfulOrderEvent(orderEvent)
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

    private fun handleSuccessfulOrderEvent(orderEvent: OrderStateEvent.ExecutionReport) {
        when (orderEvent) {
            is OrderStateEvent.ExecutionReport.Trade -> {
                val snapshot = orderStore.get(orderEvent.clOrdId)
                    ?: throw BotFatalException("Order disappeared after successful apply: ${orderEvent.clOrdId}")
                positionTracker.onFill(snapshot.side, orderEvent.fillQty, orderEvent.fillPrice)
                logger.info {
                    "Fill: ${orderEvent.fillQty} @ ${orderEvent.fillPrice}, " +
                        "position=${positionTracker.getPosition()}, pnl=${positionTracker.getRealizedPnl()}"
                }
            }
            is OrderStateEvent.ExecutionReport.Accepted -> {
                logger.info { "Order accepted: ${orderEvent.orderId} (${orderEvent.clOrdId})" }
            }
            is OrderStateEvent.ExecutionReport.Canceled -> {
                logger.info { "Order canceled: ${orderEvent.orderId} - ${orderEvent.reason}" }
            }
            is OrderStateEvent.ExecutionReport.Amended -> {
                logger.info { "Order amended: ${orderEvent.previousOrderId} -> ${orderEvent.orderId}" }
            }
            is OrderStateEvent.ExecutionReport.Rejected -> {
                throw BotFatalException("Order rejected: ${orderEvent.clOrdId} - ${orderEvent.reason}")
            }
            else -> {}
        }
    }
```

**Step 2: Remove applyOrderEvent() method**

Delete the `applyOrderEvent()` method (lines 126-142) - it's no longer needed.

**Step 3: Remove unused Event imports**

Update imports - remove references to Event.OrderAccepted, Event.OrderFill, etc. if no longer used elsewhere in the file.

**Step 4: Run build**

Run: `bazel build //bot`
Expected: Build succeeds

**Step 5: Run lint**

Run: `bazel test //bot:ktlint`
Expected: Pass (fix any issues)

**Step 6: Commit**

```bash
git add bot/
git commit -m "Simplify TradingHandler to use orderEvent directly with fail-fast"
```

---

### Task 8: Remove Order Events from core/Event.kt

**Files:**
- Modify: `core/src/main/kotlin/com/didrikquant/core/Event.kt:24-67`

**Step 1: Remove order-related event classes**

Delete the following from Event.kt:
- `OrderAccepted` (lines 24-32)
- `OrderFill` (lines 34-45)
- `OrderCanceled` (lines 47-52)
- `OrderAmended` (lines 54-61)
- `OrderRejected` (lines 63-67)

**Step 2: Run full build**

Run: `bazel build //...`
Expected: Build succeeds (all consumers updated)

**Step 3: Run all tests**

Run: `bazel test //...`
Expected: All tests pass

**Step 4: Commit**

```bash
git add core/src/main/kotlin/com/didrikquant/core/Event.kt
git commit -m "Remove order events from core Event sealed class"
```

---

### Task 9: Final Cleanup and Verification

**Files:**
- Various cleanup

**Step 1: Run full test suite**

Run: `bazel test //...`
Expected: All 12 tests pass

**Step 2: Run lint on all modules**

Run: `bazel test //... --test_tag_filters=ktlint`
Expected: All lint checks pass

**Step 3: Fix any lint issues**

Run: `bazel run //order-state:ktlint_fix && bazel run //core:ktlint_fix && bazel run //kraken-client:ktlint_fix && bazel run //bot:ktlint_fix`

**Step 4: Final commit if needed**

```bash
git add -A
git commit -m "Fix lint issues from fill handling redesign"
```

**Step 5: Create summary commit (optional squash)**

Review git log and optionally squash commits for cleaner history.

---

## Summary of Changes

| File | Change |
|------|--------|
| `OrderStateEvent.kt` | Add `Trade`, remove `PartialFill`/`Filled` |
| `OrderSnapshot.kt` | Rename `leavesQty` → `remainingQty`, add `applyTrade()`, remove `applyPartialFill()`/`applyFilled()` |
| `MutableEvent.kt` | Add `orderEvent: OrderStateEvent?` field |
| `Event.kt` | Remove `OrderAccepted`, `OrderFill`, `OrderCanceled`, `OrderAmended`, `OrderRejected` |
| `KrakenPrivateWs.kt` | Publish `OrderStateEvent` via `publishOrderEvent()` |
| `TradingHandler.kt` | Process `orderEvent` directly, fail-fast on errors |
| `TradeEventTests.kt` | New test file for Trade event |
| `OrderSnapshotExampleTests.kt` | Update to use Trade instead of PartialFill/Filled |
