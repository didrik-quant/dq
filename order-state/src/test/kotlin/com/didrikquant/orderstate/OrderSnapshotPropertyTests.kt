@file:Suppress("unused")

package com.didrikquant.orderstate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.checkAll
import java.math.BigDecimal

/**
 * Property-based tests for OrderSnapshot.
 *
 * These tests verify invariants that should hold for all possible inputs,
 * using randomly generated test data.
 */
internal class OrderSnapshotPropertyTests : FunSpec({

    // ============================================================
    // STATE MACHINE PROPERTIES
    // ============================================================

    test("terminal states stays terminal for all execution reports") {
        checkAll(
            OrderStateArbs.snapshotInTerminalState(),
            OrderStateArbs.executionReport(),
        ) { snapshot, event ->
            val result = snapshot.apply(event.withClOrdId(snapshot.clOrdId))
            result.shouldBeInstanceOf<TransitionResult.UnchangedTerminal>()
        }
    }

    test("terminal state is preserved after invalid transition attempt") {
        checkAll(
            OrderStateArbs.snapshotInTerminalState(),
            OrderStateArbs.executionReport(),
        ) { snapshot, event ->
            val originalState = snapshot.state
            snapshot.apply(event.withClOrdId(snapshot.clOrdId))
            // Immutable - original unchanged
            snapshot.state shouldBe originalState
        }
    }

    test("successful transitions produce new snapshot instance") {
        checkAll(OrderStateArbs.validTransition()) { (snapshot, event) ->
            val newSnapshot = when (val result = snapshot.apply(event)) {
                is TransitionResult.Success -> result.snapshot
                is TransitionResult.SuccessWithWarning -> result.snapshot
                else -> null
            }
            if (newSnapshot != null) {
                newSnapshot shouldNotBe snapshot
                newSnapshot.clOrdId shouldBe snapshot.clOrdId
            }
        }
    }

    test("instructions never change state") {
        checkAll(
            OrderStateArbs.snapshot(),
            OrderStateArbs.instruction(),
        ) { snapshot, instruction ->
            if (!snapshot.state.isTerminal()) {
                val result = snapshot.apply(instruction.withClOrdId(snapshot.clOrdId))
                result.shouldBeInstanceOf<TransitionResult.Unchanged>()
                result.snapshot shouldBe snapshot
            }
        }
    }

    // ============================================================
    // QUANTITY INVARIANTS
    // ============================================================

    test("filledQty never exceeds currentQty") {
        checkAll(OrderStateArbs.validEventSequence()) { events ->
            var snapshot = OrderSnapshot.fromInstruction(
                events.first() as OrderStateEvent.Instruction.Create,
            )

            events.drop(1).forEach { event ->
                val result = snapshot.apply(event)
                if (result is TransitionResult.Success) {
                    snapshot = result.snapshot
                    snapshot.filledQty shouldBeLessThanOrEqualTo snapshot.currentQty
                }
            }
        }
    }

    test("remainingQty equals currentQty minus filledQty") {
        checkAll(OrderStateArbs.validEventSequence()) { events ->
            var snapshot = OrderSnapshot.fromInstruction(
                events.first() as OrderStateEvent.Instruction.Create,
            )

            events.drop(1).forEach { event ->
                val result = snapshot.apply(event)
                if (result is TransitionResult.Success) {
                    snapshot = result.snapshot
                    snapshot.remainingQty shouldBe (snapshot.currentQty - snapshot.filledQty)
                }
            }
        }
    }

    test("filledQty is monotonically non-decreasing") {
        checkAll(OrderStateArbs.validEventSequence()) { events ->
            var snapshot = OrderSnapshot.fromInstruction(
                events.first() as OrderStateEvent.Instruction.Create,
            )
            var prevFilledQty = BigDecimal.ZERO

            events.drop(1).forEach { event ->
                val result = snapshot.apply(event)
                if (result is TransitionResult.Success) {
                    snapshot = result.snapshot
                    snapshot.filledQty shouldBeGreaterThanOrEqualTo prevFilledQty
                    prevFilledQty = snapshot.filledQty
                }
            }
        }
    }

    test("FILLED state implies remainingQty is zero") {
        checkAll(OrderStateArbs.validEventSequenceEndingInFilled()) { events ->
            var snapshot = OrderSnapshot.fromInstruction(
                events.first() as OrderStateEvent.Instruction.Create,
            )

            events.drop(1).forEach { event ->
                val result = snapshot.apply(event)
                if (result is TransitionResult.Success) {
                    snapshot = result.snapshot
                }
            }

            snapshot.state shouldBe OrderState.FILLED
            snapshot.remainingQty shouldBeLessThanOrEqualTo BigDecimal.ZERO
        }
    }

    // ============================================================
    // PRICE INVARIANTS
    // ============================================================

    test("all generated prices are positive") {
        checkAll(OrderStateArbs.orderStateEvent()) { event ->
            when (event) {
                is OrderStateEvent.Instruction.Create -> {
                    event.price shouldBeGreaterThan BigDecimal.ZERO
                    event.qty shouldBeGreaterThan BigDecimal.ZERO
                }

                is OrderStateEvent.Instruction.Amend -> {
                    event.newPrice?.let { it shouldBeGreaterThan BigDecimal.ZERO }
                    event.newQty?.let { it shouldBeGreaterThan BigDecimal.ZERO }
                }

                is OrderStateEvent.ExecutionReport.Accepted -> {
                    event.price shouldBeGreaterThan BigDecimal.ZERO
                    event.qty shouldBeGreaterThan BigDecimal.ZERO
                }

                is OrderStateEvent.ExecutionReport.Trade -> {
                    event.fillPrice shouldBeGreaterThan BigDecimal.ZERO
                    event.fillQty shouldBeGreaterThan BigDecimal.ZERO
                }

                is OrderStateEvent.ExecutionReport.Amended -> {
                    event.newPrice?.let { it shouldBeGreaterThan BigDecimal.ZERO }
                }

                else -> {}
            }
        }
    }

    test("avgFillPrice is bounded by individual fill prices") {
        checkAll(OrderStateArbs.validEventSequenceWithFills()) { events ->
            var snapshot = OrderSnapshot.fromInstruction(
                events.first() as OrderStateEvent.Instruction.Create,
            )
            val fillPrices = mutableListOf<BigDecimal>()

            events.drop(1).forEach { event ->
                if (event is OrderStateEvent.ExecutionReport.Trade) {
                    fillPrices.add(event.fillPrice)
                }
                val result = snapshot.apply(event)
                if (result is TransitionResult.Success) {
                    snapshot = result.snapshot
                }
            }

            if (fillPrices.isNotEmpty() && snapshot.avgFillPrice != null) {
                snapshot.avgFillPrice!! shouldBeGreaterThanOrEqualTo fillPrices.minOrNull()!!
                snapshot.avgFillPrice!! shouldBeLessThanOrEqualTo fillPrices.maxOrNull()!!
            }
        }
    }

    // ============================================================
    // TIMESTAMP INVARIANTS
    // ============================================================

    test("all timestamps are positive") {
        checkAll(OrderStateArbs.orderStateEvent()) { event ->
            event.timestamp shouldBeGreaterThan 0L
        }
    }

    test("lastUpdateTimestamp advances with each successful transition") {
        checkAll(OrderStateArbs.validEventSequence()) { events ->
            var snapshot = OrderSnapshot.fromInstruction(
                events.first() as OrderStateEvent.Instruction.Create,
            )
            var prevTimestamp = snapshot.lastUpdateTimestamp

            events.drop(1).forEach { event ->
                val result = snapshot.apply(event)
                if (result is TransitionResult.Success) {
                    snapshot = result.snapshot
                    snapshot.lastUpdateTimestamp shouldBeGreaterThanOrEqualTo prevTimestamp
                    prevTimestamp = snapshot.lastUpdateTimestamp
                }
            }
        }
    }

    // ============================================================
    // EVENT SEQUENCE VALIDITY
    // ============================================================

    test("Accepted must occur before fills") {
        checkAll(OrderStateArbs.validEventSequenceWithFills()) { events ->
            val acceptedIndex = events.indexOfFirst {
                it is OrderStateEvent.ExecutionReport.Accepted
            }
            val firstFillIndex = events.indexOfFirst {
                it is OrderStateEvent.ExecutionReport.Trade
            }

            if (firstFillIndex >= 0) {
                acceptedIndex shouldBeLessThanOrEqualTo firstFillIndex
            }
        }
    }

    test("at most one terminal event per sequence") {
        checkAll(OrderStateArbs.validEventSequence()) { events ->
            val terminalCount = events.count { event ->
                event is OrderStateEvent.ExecutionReport.Canceled ||
                    event is OrderStateEvent.ExecutionReport.Rejected ||
                    event is OrderStateEvent.ExecutionReport.Expired
            }
            terminalCount shouldBeLessThanOrEqualTo 1
        }
    }

    // ============================================================
    // IMMUTABILITY PROPERTIES
    // ============================================================

    test("original snapshot is unchanged after apply") {
        checkAll(
            OrderStateArbs.snapshot(),
            OrderStateArbs.executionReport(),
        ) { original, event ->
            val originalState = original.state
            val originalFilledQty = original.filledQty
            val originalPrice = original.currentPrice

            original.apply(event.withClOrdId(original.clOrdId))

            // Original should be completely unchanged
            original.state shouldBe originalState
            original.filledQty shouldBe originalFilledQty
            original.currentPrice shouldBe originalPrice
        }
    }

    test("applying same event twice yields equal snapshots") {
        checkAll(OrderStateArbs.validTransition()) { (snapshot, event) ->
            val result1 = snapshot.apply(event)
            val result2 = snapshot.apply(event)

            result1::class shouldBe result2::class
            if (result1 is TransitionResult.Success && result2 is TransitionResult.Success) {
                result1.snapshot shouldBe result2.snapshot
            }
        }
    }

    // ============================================================
    // CLORDID VALIDATION
    // ============================================================

    test("events with wrong clOrdId are rejected") {
        checkAll(
            OrderStateArbs.snapshot(),
            OrderStateArbs.executionReport(),
            OrderStateArbs.clOrdId(),
        ) { snapshot, event, differentClOrdId ->
            if (differentClOrdId != snapshot.clOrdId && !snapshot.state.isTerminal()) {
                val wrongEvent = event.withClOrdId(differentClOrdId)
                val result = snapshot.apply(wrongEvent)
                result.shouldBeInstanceOf<TransitionResult.Invalid>()
            }
        }
    }

    // ============================================================
    // STATE CONSISTENCY
    // ============================================================

    test("hasFills is true iff filledQty > 0") {
        checkAll(OrderStateArbs.snapshot()) { snapshot ->
            snapshot.hasFills shouldBe (snapshot.filledQty > BigDecimal.ZERO)
        }
    }

    test("isTerminal matches state.isTerminal()") {
        checkAll(OrderStateArbs.snapshot()) { snapshot ->
            snapshot.isTerminal shouldBe snapshot.state.isTerminal()
        }
    }

    test("isActive matches state.isActive()") {
        checkAll(OrderStateArbs.snapshot()) { snapshot ->
            snapshot.isActive shouldBe snapshot.state.isActive()
        }
    }

    test("avgFillPrice is null iff filledQty is zero") {
        checkAll(OrderStateArbs.validEventSequence()) { events ->
            var snapshot = OrderSnapshot.fromInstruction(
                events.first() as OrderStateEvent.Instruction.Create,
            )

            events.drop(1).forEach { event ->
                val result = snapshot.apply(event)
                if (result is TransitionResult.Success) {
                    snapshot = result.snapshot
                    if (snapshot.filledQty == BigDecimal.ZERO) {
                        snapshot.avgFillPrice shouldBe null
                    } else {
                        snapshot.avgFillPrice shouldNotBe null
                    }
                }
            }
        }
    }

    // ============================================================
    // TRANSITION RESULT PROPERTIES
    // ============================================================

    test("TransitionResult.snapshotOrNull returns correct value") {
        checkAll(OrderStateArbs.validTransition()) { (snapshot, event) ->
            when (val result = snapshot.apply(event)) {
                is TransitionResult.Success -> {
                    result.snapshotOrNull() shouldBe result.snapshot
                    result.isOk() shouldBe true
                }

                is TransitionResult.SuccessWithWarning -> {
                    result.snapshotOrNull() shouldBe result.snapshot
                    result.isOk() shouldBe true
                }

                is TransitionResult.Unchanged -> {
                    result.snapshotOrNull() shouldBe result.snapshot
                    result.isOk() shouldBe true
                }

                is TransitionResult.Duplicate -> {
                    result.snapshotOrNull() shouldBe null
                    result.isOk() shouldBe false
                }

                is TransitionResult.Invalid -> {
                    result.snapshotOrNull() shouldBe null
                    result.isOk() shouldBe false
                }

                is TransitionResult.UnchangedTerminal -> {
                    result.snapshotOrNull() shouldBe result.snapshot
                    result.isOk() shouldBe true
                }
            }
        }
    }
})
