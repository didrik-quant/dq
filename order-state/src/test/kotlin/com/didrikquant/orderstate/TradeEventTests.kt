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
