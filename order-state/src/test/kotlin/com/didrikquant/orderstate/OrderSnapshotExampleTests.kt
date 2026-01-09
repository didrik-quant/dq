package com.didrikquant.orderstate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal

/** Helper to compare BigDecimals numerically (ignoring scale) */
private infix fun BigDecimal.shouldBeNumerically(expected: BigDecimal) {
    if (this.compareTo(expected) != 0) {
        throw AssertionError("expected:<$expected> but was:<$this>")
    }
}

/**
 * Example-based tests for OrderSnapshot.
 *
 * These tests verify specific scenarios with concrete values,
 * complementing the property-based tests.
 */
internal class OrderSnapshotExampleTests : FunSpec({

    // ============================================================
    // HAPPY PATH TESTS
    // ============================================================

    test("full order lifecycle: create -> accept -> partial fill -> filled") {
        // Create order
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-001",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 1000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        snapshot.state shouldBe OrderState.PENDING_NEW
        snapshot.clOrdId shouldBe "order-001"
        snapshot.orderId shouldBe ""
        snapshot.filledQty shouldBe BigDecimal.ZERO
        snapshot.leavesQty shouldBe BigDecimal("10.00")

        // Accept order
        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-001",
            orderId = "exchange-order-123",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 1001L,
        )
        val acceptResult = snapshot.apply(accepted)
        acceptResult.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (acceptResult as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.OPEN
        snapshot.orderId shouldBe "exchange-order-123"

        // Partial fill
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
        val partialResult = snapshot.apply(partialFill)
        partialResult.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (partialResult as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.PARTIALLY_FILLED
        snapshot.filledQty shouldBe BigDecimal("3.00")
        snapshot.leavesQty shouldBe BigDecimal("7.00")
        snapshot.avgFillPrice shouldBe BigDecimal("99.50")

        // Final fill
        val filled = OrderStateEvent.ExecutionReport.Filled(
            clOrdId = "order-001",
            orderId = "exchange-order-123",
            execId = "exec-002",
            fillQty = BigDecimal("7.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("10.00"),
            timestamp = 1003L,
        )
        val filledResult = snapshot.apply(filled)
        filledResult.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (filledResult as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.FILLED
        snapshot.filledQty shouldBe BigDecimal("10.00")
        snapshot.leavesQty shouldBeNumerically BigDecimal.ZERO
        snapshot.isTerminal shouldBe true
    }

    test("order rejected before acceptance") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-002",
            side = Side.SELL,
            price = BigDecimal("50.00"),
            qty = BigDecimal("5.00"),
            timestamp = 2000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val rejected = OrderStateEvent.ExecutionReport.Rejected.beforeAcceptance(
            clOrdId = "order-002",
            reason = "insufficient_funds",
            timestamp = 2001L,
        )
        val result = snapshot.apply(rejected)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (result as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.REJECTED
        snapshot.isTerminal shouldBe true
        snapshot.filledQty shouldBe BigDecimal.ZERO
    }

    test("order canceled after partial fill") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-003",
            side = Side.BUY,
            price = BigDecimal("200.00"),
            qty = BigDecimal("20.00"),
            timestamp = 3000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        // Accept
        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-003",
            orderId = "ex-456",
            side = Side.BUY,
            price = BigDecimal("200.00"),
            qty = BigDecimal("20.00"),
            timestamp = 3001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        // Partial fill
        val partialFill = OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = "order-003",
            orderId = "ex-456",
            execId = "exec-003",
            fillQty = BigDecimal("5.00"),
            fillPrice = BigDecimal("199.00"),
            cumQty = BigDecimal("5.00"),
            leavesQty = BigDecimal("15.00"),
            timestamp = 3002L,
        )
        snapshot = (snapshot.apply(partialFill) as TransitionResult.Success).snapshot

        // Cancel remaining
        val canceled = OrderStateEvent.ExecutionReport.Canceled(
            clOrdId = "order-003",
            orderId = "ex-456",
            reason = "user_requested",
            timestamp = 3003L,
        )
        val result = snapshot.apply(canceled)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (result as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.CANCELED
        snapshot.isTerminal shouldBe true
        // Partial fill is preserved
        snapshot.filledQty shouldBe BigDecimal("5.00")
        snapshot.hasFills shouldBe true
    }

    test("order amended then filled") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-004",
            side = Side.SELL,
            price = BigDecimal("150.00"),
            qty = BigDecimal("10.00"),
            timestamp = 4000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        // Accept
        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-004",
            orderId = "ex-789",
            side = Side.SELL,
            price = BigDecimal("150.00"),
            qty = BigDecimal("10.00"),
            timestamp = 4001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        snapshot.currentPrice shouldBe BigDecimal("150.00")
        snapshot.originalPrice shouldBe BigDecimal("150.00")

        // Amend price
        val amended = OrderStateEvent.ExecutionReport.Amended(
            clOrdId = "order-004",
            orderId = "ex-790",
            previousOrderId = "ex-789",
            newPrice = BigDecimal("145.00"),
            newQty = null,
            timestamp = 4002L,
        )
        snapshot = (snapshot.apply(amended) as TransitionResult.Success).snapshot

        snapshot.orderId shouldBe "ex-790"
        snapshot.currentPrice shouldBe BigDecimal("145.00")
        snapshot.originalPrice shouldBe BigDecimal("150.00")
        snapshot.state shouldBe OrderState.OPEN

        // Fill at new price
        val filled = OrderStateEvent.ExecutionReport.Filled(
            clOrdId = "order-004",
            orderId = "ex-790",
            execId = "exec-004",
            fillQty = BigDecimal("10.00"),
            fillPrice = BigDecimal("145.00"),
            cumQty = BigDecimal("10.00"),
            timestamp = 4003L,
        )
        snapshot = (snapshot.apply(filled) as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.FILLED
    }

    // ============================================================
    // ERROR CASES
    // ============================================================

    test("cannot apply event with wrong clOrdId") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-005",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 5000L,
        )
        val snapshot = OrderSnapshot.fromInstruction(create)

        val wrongEvent = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "wrong-order",
            orderId = "ex-999",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 5001L,
        )

        val result = snapshot.apply(wrongEvent)
        result.shouldBeInstanceOf<TransitionResult.Invalid>()
        (result as TransitionResult.Invalid).reason shouldBe
            "Event clOrdId 'wrong-order' doesn't match order 'order-005'"
    }

    test("fill can be applied to pending_new order (race condition handling)") {
        // This handles the race condition where a fill arrives before the Accepted event
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-006",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 6000L,
        )
        val snapshot = OrderSnapshot.fromInstruction(create)

        val fill = OrderStateEvent.ExecutionReport.Filled(
            clOrdId = "order-006",
            orderId = "ex-111",
            execId = "exec-006",
            fillQty = BigDecimal("5.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("5.00"),
            timestamp = 6001L,
        )

        val result = snapshot.apply(fill)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        val filled = (result as TransitionResult.Success).snapshot
        filled.state shouldBe OrderState.FILLED
        filled.filledQty shouldBe BigDecimal("5.00")
    }

    test("cannot apply events to terminal state") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-007",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 7000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val rejected = OrderStateEvent.ExecutionReport.Rejected.beforeAcceptance(
            clOrdId = "order-007",
            reason = "market_closed",
            timestamp = 7001L,
        )
        snapshot = (snapshot.apply(rejected) as TransitionResult.Success).snapshot
        snapshot.state shouldBe OrderState.REJECTED

        // Try to accept after rejection
        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-007",
            orderId = "ex-222",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 7002L,
        )

        val result = snapshot.apply(accepted)
        result.shouldBeInstanceOf<TransitionResult.Invalid>()
    }

    test("cannot reject after accepted") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-008",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 8000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-008",
            orderId = "ex-333",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 8001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        val rejected = OrderStateEvent.ExecutionReport.Rejected(
            clOrdId = "order-008",
            orderId = "ex-333",
            reason = "some_reason",
            timestamp = 8002L,
        )

        val result = snapshot.apply(rejected)
        result.shouldBeInstanceOf<TransitionResult.Invalid>()
    }

    // ============================================================
    // INSTRUCTION EVENTS
    // ============================================================

    test("instruction events return Unchanged result") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-009",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 9000L,
        )
        val snapshot = OrderSnapshot.fromInstruction(create)

        val cancelInstruction = OrderStateEvent.Instruction.Cancel(
            clOrdId = "order-009",
            timestamp = 9001L,
        )

        val result = snapshot.apply(cancelInstruction)
        result.shouldBeInstanceOf<TransitionResult.Unchanged>()
        (result as TransitionResult.Unchanged).snapshot shouldBe snapshot
    }

    // ============================================================
    // AVERAGE FILL PRICE CALCULATION
    // ============================================================

    test("average fill price is weighted correctly") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-010",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 10000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        // Accept
        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-010",
            orderId = "ex-444",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 10001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        // First fill: 4 units @ 100
        val fill1 = OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = "order-010",
            orderId = "ex-444",
            execId = "exec-010a",
            fillQty = BigDecimal("4.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("4.00"),
            leavesQty = BigDecimal("6.00"),
            timestamp = 10002L,
        )
        snapshot = (snapshot.apply(fill1) as TransitionResult.Success).snapshot
        snapshot.avgFillPrice!! shouldBeNumerically BigDecimal("100.00")

        // Second fill: 6 units @ 102
        // Weighted avg = (4*100 + 6*102) / 10 = (400 + 612) / 10 = 101.2
        val fill2 = OrderStateEvent.ExecutionReport.Filled(
            clOrdId = "order-010",
            orderId = "ex-444",
            execId = "exec-010b",
            fillQty = BigDecimal("6.00"),
            fillPrice = BigDecimal("102.00"),
            cumQty = BigDecimal("10.00"),
            timestamp = 10003L,
        )
        snapshot = (snapshot.apply(fill2) as TransitionResult.Success).snapshot
        snapshot.avgFillPrice!! shouldBeNumerically BigDecimal("101.20")
    }

    // ============================================================
    // SIDE TESTS
    // ============================================================

    test("Side.opposite returns correct value") {
        Side.BUY.opposite() shouldBe Side.SELL
        Side.SELL.opposite() shouldBe Side.BUY
    }

    // ============================================================
    // ORDER STATE TESTS
    // ============================================================

    test("OrderState terminal states are correct") {
        OrderState.FILLED.isTerminal() shouldBe true
        OrderState.CANCELED.isTerminal() shouldBe true
        OrderState.REJECTED.isTerminal() shouldBe true
        OrderState.EXPIRED.isTerminal() shouldBe true

        OrderState.PENDING_NEW.isTerminal() shouldBe false
        OrderState.OPEN.isTerminal() shouldBe false
        OrderState.PARTIALLY_FILLED.isTerminal() shouldBe false
    }

    test("OrderState active states are correct") {
        OrderState.OPEN.isActive() shouldBe true
        OrderState.PARTIALLY_FILLED.isActive() shouldBe true

        OrderState.PENDING_NEW.isActive() shouldBe false
        OrderState.FILLED.isActive() shouldBe false
        OrderState.CANCELED.isActive() shouldBe false
        OrderState.REJECTED.isActive() shouldBe false
        OrderState.EXPIRED.isActive() shouldBe false
    }

    // ============================================================
    // RESTATED EVENT
    // ============================================================

    test("order restated by exchange") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-011",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 11000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-011",
            orderId = "ex-555",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 11001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        // Exchange restates the order (e.g., margin maintenance)
        val restated = OrderStateEvent.ExecutionReport.Restated(
            clOrdId = "order-011",
            orderId = "ex-555",
            reason = RestateReason.MARGIN_MAINTENANCE,
            newPrice = null,
            newQty = BigDecimal("8.00"),
            timestamp = 11002L,
        )
        snapshot = (snapshot.apply(restated) as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.OPEN
        snapshot.currentQty shouldBe BigDecimal("8.00")
        snapshot.originalQty shouldBe BigDecimal("10.00")
        snapshot.currentPrice shouldBe BigDecimal("100.00")
    }

    // ============================================================
    // EXPIRED EVENT
    // ============================================================

    test("order expires") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-012",
            side = Side.SELL,
            price = BigDecimal("200.00"),
            qty = BigDecimal("5.00"),
            timestamp = 12000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-012",
            orderId = "ex-666",
            side = Side.SELL,
            price = BigDecimal("200.00"),
            qty = BigDecimal("5.00"),
            timestamp = 12001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        val expired = OrderStateEvent.ExecutionReport.Expired(
            clOrdId = "order-012",
            orderId = "ex-666",
            timestamp = 12002L,
        )
        snapshot = (snapshot.apply(expired) as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.EXPIRED
        snapshot.isTerminal shouldBe true
        snapshot.filledQty shouldBe BigDecimal.ZERO
    }

    // ============================================================
    // SAFETY TESTS - These test critical safety invariants
    // ============================================================

    test("duplicate execId is rejected to prevent double-counting fills") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-dup-001",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 20000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-dup-001",
            orderId = "ex-dup",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 20001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        // First fill - should succeed
        val fill1 = OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = "order-dup-001",
            orderId = "ex-dup",
            execId = "exec-same-id",
            fillQty = BigDecimal("5.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("5.00"),
            leavesQty = BigDecimal("5.00"),
            timestamp = 20002L,
        )
        val result1 = snapshot.apply(fill1)
        result1.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (result1 as TransitionResult.Success).snapshot
        snapshot.filledQty shouldBe BigDecimal("5.00")

        // Duplicate fill with same execId - should be rejected
        val fill2 = OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = "order-dup-001",
            orderId = "ex-dup",
            execId = "exec-same-id", // Same execId!
            fillQty = BigDecimal("5.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("10.00"),
            leavesQty = BigDecimal("0.00"),
            timestamp = 20003L,
        )
        val result2 = snapshot.apply(fill2)
        result2.shouldBeInstanceOf<TransitionResult.Duplicate>()

        // filledQty should NOT have changed
        snapshot.filledQty shouldBe BigDecimal("5.00")
    }

    test("PendingNew event sets orderId while staying in PENDING_NEW state") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-pn-001",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("5.00"),
            timestamp = 21000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)
        snapshot.orderId shouldBe ""

        val pendingNew = OrderStateEvent.ExecutionReport.PendingNew(
            clOrdId = "order-pn-001",
            orderId = "ex-pending-123",
            timestamp = 21001L,
        )
        val result = snapshot.apply(pendingNew)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (result as TransitionResult.Success).snapshot

        // Should still be PENDING_NEW but with orderId set
        snapshot.state shouldBe OrderState.PENDING_NEW
        snapshot.orderId shouldBe "ex-pending-123"
    }

    test("amend quantity only without changing price") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-amend-qty",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 22000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-amend-qty",
            orderId = "ex-amend-1",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 22001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        // Amend quantity only - price stays the same
        val amended = OrderStateEvent.ExecutionReport.Amended(
            clOrdId = "order-amend-qty",
            orderId = "ex-amend-2",
            previousOrderId = "ex-amend-1",
            newPrice = null, // No price change
            newQty = BigDecimal("5.00"),
            timestamp = 22002L,
        )
        val result = snapshot.apply(amended)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (result as TransitionResult.Success).snapshot

        snapshot.currentPrice shouldBe BigDecimal("100.00") // Price unchanged
        snapshot.currentQty shouldBe BigDecimal("5.00") // Qty changed
    }

    test("partial fill then expired preserves fill information") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-pf-exp",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 23000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-pf-exp",
            orderId = "ex-pf-exp",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 23001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        // Partial fill
        val partialFill = OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = "order-pf-exp",
            orderId = "ex-pf-exp",
            execId = "exec-pf-exp",
            fillQty = BigDecimal("3.00"),
            fillPrice = BigDecimal("99.00"),
            cumQty = BigDecimal("3.00"),
            leavesQty = BigDecimal("7.00"),
            timestamp = 23002L,
        )
        snapshot = (snapshot.apply(partialFill) as TransitionResult.Success).snapshot
        snapshot.state shouldBe OrderState.PARTIALLY_FILLED

        // Order expires (e.g., GTD expiration)
        val expired = OrderStateEvent.ExecutionReport.Expired(
            clOrdId = "order-pf-exp",
            orderId = "ex-pf-exp",
            timestamp = 23003L,
        )
        val result = snapshot.apply(expired)
        result.shouldBeInstanceOf<TransitionResult.Success>()
        snapshot = (result as TransitionResult.Success).snapshot

        snapshot.state shouldBe OrderState.EXPIRED
        snapshot.isTerminal shouldBe true
        // Partial fill should be preserved
        snapshot.filledQty shouldBe BigDecimal("3.00")
        snapshot.hasFills shouldBe true
        snapshot.avgFillPrice shouldBe BigDecimal("99.00")
    }

    test("cumQty mismatch is detected and reported") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-cumqty",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 24000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-cumqty",
            orderId = "ex-cumqty",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 24001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        // First fill
        val fill1 = OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = "order-cumqty",
            orderId = "ex-cumqty",
            execId = "exec-cumqty-1",
            fillQty = BigDecimal("3.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("3.00"),
            leavesQty = BigDecimal("7.00"),
            timestamp = 24002L,
        )
        snapshot = (snapshot.apply(fill1) as TransitionResult.Success).snapshot

        // Second fill with WRONG cumQty (says 8.00 but should be 3+2=5)
        val fill2 = OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = "order-cumqty",
            orderId = "ex-cumqty",
            execId = "exec-cumqty-2",
            fillQty = BigDecimal("2.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("8.00"), // WRONG! Should be 5.00
            leavesQty = BigDecimal("2.00"),
            timestamp = 24003L,
        )
        val result = snapshot.apply(fill2)
        // Should return a warning result indicating the mismatch
        result.shouldBeInstanceOf<TransitionResult.SuccessWithWarning>()
        val warning = (result as TransitionResult.SuccessWithWarning).warning
        warning shouldBe "cumQty mismatch: expected 5.00 but got 8.00"
    }

    test("appliedExecIds tracks all processed fills") {
        val create = OrderStateEvent.Instruction.Create(
            clOrdId = "order-track-exec",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 25000L,
        )
        var snapshot = OrderSnapshot.fromInstruction(create)
        snapshot.appliedExecIds shouldBe emptySet()

        val accepted = OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = "order-track-exec",
            orderId = "ex-track",
            side = Side.BUY,
            price = BigDecimal("100.00"),
            qty = BigDecimal("10.00"),
            timestamp = 25001L,
        )
        snapshot = (snapshot.apply(accepted) as TransitionResult.Success).snapshot

        val fill1 = OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = "order-track-exec",
            orderId = "ex-track",
            execId = "exec-track-001",
            fillQty = BigDecimal("3.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("3.00"),
            leavesQty = BigDecimal("7.00"),
            timestamp = 25002L,
        )
        snapshot = (snapshot.apply(fill1) as TransitionResult.Success).snapshot
        snapshot.appliedExecIds shouldBe setOf("exec-track-001")

        val fill2 = OrderStateEvent.ExecutionReport.Filled(
            clOrdId = "order-track-exec",
            orderId = "ex-track",
            execId = "exec-track-002",
            fillQty = BigDecimal("7.00"),
            fillPrice = BigDecimal("100.00"),
            cumQty = BigDecimal("10.00"),
            timestamp = 25003L,
        )
        snapshot = (snapshot.apply(fill2) as TransitionResult.Success).snapshot
        snapshot.appliedExecIds shouldBe setOf("exec-track-001", "exec-track-002")
    }
})
