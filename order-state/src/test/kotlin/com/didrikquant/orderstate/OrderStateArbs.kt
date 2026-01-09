package com.didrikquant.orderstate

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Kotest Arb generators for order-state types.
 */
internal object OrderStateArbs {

    private val alphanumericChars = ('a'..'z') + ('0'..'9')

    internal fun clOrdId(): Arb<String> = arbitrary {
        buildString {
            repeat(18) {
                append(alphanumericChars.random())
            }
        }
    }

    internal fun orderId(): Arb<String> = arbitrary {
        buildString {
            repeat(24) {
                append(alphanumericChars.random())
            }
        }
    }

    internal fun execId(): Arb<String> = arbitrary {
        buildString {
            repeat(24) {
                append(alphanumericChars.random())
            }
        }
    }

    internal fun side(): Arb<Side> = Arb.enum<Side>()

    internal fun price(): Arb<BigDecimal> = Arb.double(0.0001..100000.0)
        .filter { it.isFinite() }
        .map { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP) }

    internal fun qty(): Arb<BigDecimal> = Arb.double(0.0001..10000.0)
        .filter { it.isFinite() }
        .map { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP) }

    internal fun timestamp(): Arb<Long> = Arb.long(1_000_000_000_000L..2_000_000_000_000L)

    internal fun reason(): Arb<String> = Arb.of(
        "user_requested",
        "insufficient_funds",
        "invalid_price",
        "market_closed",
        "self_trade_prevention",
        "post_only_rejected",
    )

    internal fun restateReason(): Arb<RestateReason> = Arb.enum<RestateReason>()

    // ============================================================
    // INSTRUCTION GENERATORS
    // ============================================================

    internal fun instructionCreate(): Arb<OrderStateEvent.Instruction.Create> = arbitrary {
        OrderStateEvent.Instruction.Create(
            clOrdId = clOrdId().bind(),
            side = side().bind(),
            price = price().bind(),
            qty = qty().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun instructionCancel(): Arb<OrderStateEvent.Instruction.Cancel> = arbitrary {
        OrderStateEvent.Instruction.Cancel(
            clOrdId = clOrdId().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun instructionAmend(): Arb<OrderStateEvent.Instruction.Amend> = arbitrary {
        OrderStateEvent.Instruction.Amend(
            clOrdId = clOrdId().bind(),
            newPrice = price().bind(),
            newQty = if (Arb.boolean().bind()) qty().bind() else null,
            timestamp = timestamp().bind(),
        )
    }

    internal fun instruction(): Arb<OrderStateEvent.Instruction> = arbitrary {
        val choice = Arb.int(0..2).bind()
        when (choice) {
            0 -> instructionCreate().bind()
            1 -> instructionCancel().bind()
            else -> instructionAmend().bind()
        }
    }

    // ============================================================
    // EXECUTION REPORT GENERATORS
    // ============================================================

    internal fun executionReportPendingNew(): Arb<OrderStateEvent.ExecutionReport.PendingNew> = arbitrary {
        OrderStateEvent.ExecutionReport.PendingNew(
            clOrdId = clOrdId().bind(),
            orderId = orderId().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun executionReportAccepted(): Arb<OrderStateEvent.ExecutionReport.Accepted> = arbitrary {
        OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = clOrdId().bind(),
            orderId = orderId().bind(),
            side = side().bind(),
            price = price().bind(),
            qty = qty().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun executionReportTrade(): Arb<OrderStateEvent.ExecutionReport.Trade> = arbitrary {
        OrderStateEvent.ExecutionReport.Trade(
            clOrdId = clOrdId().bind(),
            orderId = orderId().bind(),
            execId = execId().bind(),
            fillQty = qty().bind(),
            fillPrice = price().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun executionReportCanceled(): Arb<OrderStateEvent.ExecutionReport.Canceled> = arbitrary {
        OrderStateEvent.ExecutionReport.Canceled(
            clOrdId = clOrdId().bind(),
            orderId = orderId().bind(),
            reason = reason().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun executionReportRejected(): Arb<OrderStateEvent.ExecutionReport.Rejected> = arbitrary {
        OrderStateEvent.ExecutionReport.Rejected(
            clOrdId = clOrdId().bind(),
            orderId = orderId().bind(),
            reason = reason().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun executionReportExpired(): Arb<OrderStateEvent.ExecutionReport.Expired> = arbitrary {
        OrderStateEvent.ExecutionReport.Expired(
            clOrdId = clOrdId().bind(),
            orderId = orderId().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun executionReportAmended(): Arb<OrderStateEvent.ExecutionReport.Amended> = arbitrary {
        OrderStateEvent.ExecutionReport.Amended(
            clOrdId = clOrdId().bind(),
            orderId = orderId().bind(),
            previousOrderId = orderId().bind(),
            newPrice = if (Arb.boolean().bind()) price().bind() else null,
            newQty = if (Arb.boolean().bind()) qty().bind() else null,
            timestamp = timestamp().bind(),
        )
    }

    internal fun executionReportRestated(): Arb<OrderStateEvent.ExecutionReport.Restated> = arbitrary {
        OrderStateEvent.ExecutionReport.Restated(
            clOrdId = clOrdId().bind(),
            orderId = orderId().bind(),
            reason = restateReason().bind(),
            newPrice = if (Arb.boolean().bind()) price().bind() else null,
            newQty = if (Arb.boolean().bind()) qty().bind() else null,
            timestamp = timestamp().bind(),
        )
    }

    internal fun executionReport(): Arb<OrderStateEvent.ExecutionReport> = arbitrary {
        val choice = Arb.int(0..7).bind()
        when (choice) {
            0 -> executionReportPendingNew().bind()
            1 -> executionReportAccepted().bind()
            2 -> executionReportTrade().bind()
            3 -> executionReportCanceled().bind()
            4 -> executionReportRejected().bind()
            5 -> executionReportExpired().bind()
            6 -> executionReportAmended().bind()
            else -> executionReportRestated().bind()
        }
    }

    internal fun orderStateEvent(): Arb<OrderStateEvent> = arbitrary {
        val isInstruction = Arb.boolean().bind()
        if (isInstruction) {
            instruction().bind()
        } else {
            executionReport().bind()
        }
    }

    // ============================================================
    // SNAPSHOT GENERATORS
    // ============================================================

    internal fun snapshot(): Arb<OrderSnapshot> = arbitrary {
        val create = instructionCreate().bind()
        val state = Arb.enum<OrderState>().bind()
        val originalQty = create.qty
        val maxFilled = originalQty.toDouble() * 0.99
        val filledQty = when (state) {
            OrderState.PENDING_NEW, OrderState.REJECTED -> {
                BigDecimal.ZERO
            }

            OrderState.OPEN -> {
                BigDecimal.ZERO
            }

            OrderState.PARTIALLY_FILLED -> {
                if (maxFilled > 0.0001) {
                    Arb.double(0.0001..maxFilled)
                        .filter { it.isFinite() }
                        .map { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP) }
                        .bind()
                } else {
                    BigDecimal("0.0001")
                }
            }

            OrderState.FILLED -> {
                originalQty
            }

            OrderState.CANCELED, OrderState.EXPIRED -> {
                val maxCanceled = originalQty.toDouble() * 0.5
                if (maxCanceled > 0.0001) {
                    Arb.double(0.0..maxCanceled)
                        .filter { it.isFinite() }
                        .map { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP) }
                        .bind()
                } else {
                    BigDecimal.ZERO
                }
            }
        }

        OrderSnapshot(
            clOrdId = create.clOrdId,
            orderId = if (state == OrderState.PENDING_NEW) "" else orderId().bind(),
            side = create.side,
            originalPrice = create.price,
            currentPrice = create.price,
            originalQty = originalQty,
            currentQty = originalQty,
            filledQty = filledQty,
            avgFillPrice = if (filledQty > BigDecimal.ZERO) price().bind() else null,
            state = state,
            lastUpdateTimestamp = create.timestamp,
        )
    }

    internal fun snapshotInTerminalState(): Arb<OrderSnapshot> = snapshot().filter { it.state.isTerminal() }

    // ============================================================
    // VALID SEQUENCE GENERATORS
    // ============================================================

    internal fun validEventSequence(): Arb<List<OrderStateEvent>> = arbitrary {
        val clOrdId = clOrdId().bind()
        val orderId = orderId().bind()
        val side = side().bind()
        val originalQty = qty().bind()
        val price = price().bind()
        var ts = timestamp().bind()

        val events = mutableListOf<OrderStateEvent>()

        // Always start with Create instruction
        events.add(OrderStateEvent.Instruction.Create(clOrdId, side, price, originalQty, ts))
        ts += Arb.long(1L..1000L).bind()

        // Choose a random valid path
        val path = Arb.int(0..5).bind()

        when (path) {
            0 -> {
                // Rejected immediately
                events.add(
                    OrderStateEvent.ExecutionReport.Rejected.beforeAcceptance(
                        clOrdId,
                        "insufficient_funds",
                        ts,
                    ),
                )
            }

            1 -> {
                // Accepted then canceled
                events.add(
                    OrderStateEvent.ExecutionReport.Accepted(
                        clOrdId,
                        orderId,
                        side,
                        price,
                        originalQty,
                        ts,
                    ),
                )
                ts += Arb.long(1L..1000L).bind()
                events.add(
                    OrderStateEvent.ExecutionReport.Canceled(
                        clOrdId,
                        orderId,
                        "user_requested",
                        ts,
                    ),
                )
            }

            2 -> {
                // Accepted then filled immediately
                events.add(
                    OrderStateEvent.ExecutionReport.Accepted(
                        clOrdId,
                        orderId,
                        side,
                        price,
                        originalQty,
                        ts,
                    ),
                )
                ts += Arb.long(1L..1000L).bind()
                val fillPrice = price().bind()
                events.add(
                    OrderStateEvent.ExecutionReport.Trade(
                        clOrdId,
                        orderId,
                        execId().bind(),
                        originalQty,
                        fillPrice,
                        ts,
                    ),
                )
            }

            3 -> {
                // Accepted, partial fills, then filled
                events.add(
                    OrderStateEvent.ExecutionReport.Accepted(
                        clOrdId,
                        orderId,
                        side,
                        price,
                        originalQty,
                        ts,
                    ),
                )

                var cumQty = BigDecimal.ZERO
                val numFills = Arb.int(1..3).bind()
                val fillQty = originalQty.divide(BigDecimal(numFills + 1), 8, RoundingMode.DOWN)

                repeat(numFills) {
                    ts += Arb.long(1L..1000L).bind()
                    cumQty += fillQty
                    events.add(
                        OrderStateEvent.ExecutionReport.Trade(
                            clOrdId,
                            orderId,
                            execId().bind(),
                            fillQty,
                            price().bind(),
                            ts,
                        ),
                    )
                }

                ts += Arb.long(1L..1000L).bind()
                val lastFill = originalQty - cumQty
                events.add(
                    OrderStateEvent.ExecutionReport.Trade(
                        clOrdId,
                        orderId,
                        execId().bind(),
                        lastFill,
                        price().bind(),
                        ts,
                    ),
                )
            }

            4 -> {
                // Accepted then expired
                events.add(
                    OrderStateEvent.ExecutionReport.Accepted(
                        clOrdId,
                        orderId,
                        side,
                        price,
                        originalQty,
                        ts,
                    ),
                )
                ts += Arb.long(1L..1000L).bind()
                events.add(OrderStateEvent.ExecutionReport.Expired(clOrdId, orderId, ts))
            }

            5 -> {
                // Accepted, amended, then filled
                events.add(
                    OrderStateEvent.ExecutionReport.Accepted(
                        clOrdId,
                        orderId,
                        side,
                        price,
                        originalQty,
                        ts,
                    ),
                )
                ts += Arb.long(1L..1000L).bind()
                val newOrderId = orderId().bind()
                val newPrice = price().bind()
                events.add(
                    OrderStateEvent.ExecutionReport.Amended(
                        clOrdId,
                        newOrderId,
                        orderId,
                        newPrice,
                        null,
                        ts,
                    ),
                )
                ts += Arb.long(1L..1000L).bind()
                events.add(
                    OrderStateEvent.ExecutionReport.Trade(
                        clOrdId,
                        newOrderId,
                        execId().bind(),
                        originalQty,
                        newPrice,
                        ts,
                    ),
                )
            }
        }

        events.toList()
    }

    internal fun validEventSequenceWithFills(): Arb<List<OrderStateEvent>> =
        validEventSequence().filter { events ->
            events.any { it is OrderStateEvent.ExecutionReport.Trade }
        }

    internal fun validEventSequenceEndingInFilled(): Arb<List<OrderStateEvent>> =
        validEventSequence().filter { events ->
            events.lastOrNull() is OrderStateEvent.ExecutionReport.Trade
        }

    internal fun validTransition(): Arb<Pair<OrderSnapshot, OrderStateEvent>> = arbitrary {
        val events = validEventSequence().bind()
        if (events.size < 2) {
            // Generate minimal valid transition
            val create = events.first() as OrderStateEvent.Instruction.Create
            val snapshot = OrderSnapshot.fromInstruction(create)
            val accepted = OrderStateEvent.ExecutionReport.Accepted(
                create.clOrdId,
                orderId().bind(),
                create.side,
                create.price,
                create.qty,
                create.timestamp + 1,
            )
            Pair(snapshot, accepted)
        } else {
            // Pick a random point in the sequence
            val idx = Arb.int(1 until events.size).bind()
            var snapshot = OrderSnapshot.fromInstruction(events.first() as OrderStateEvent.Instruction.Create)
            for (i in 1 until idx) {
                val result = snapshot.apply(events[i])
                if (result is TransitionResult.Success) {
                    snapshot = result.snapshot
                }
            }
            Pair(snapshot, events[idx])
        }
    }
}

/**
 * Extension to change clOrdId on any event - useful for testing.
 */
internal fun OrderStateEvent.withClOrdId(newClOrdId: String): OrderStateEvent = when (this) {
    is OrderStateEvent.Instruction.Create -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.Instruction.Cancel -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.Instruction.Amend -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.ExecutionReport.PendingNew -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.ExecutionReport.Accepted -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.ExecutionReport.Trade -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.ExecutionReport.Canceled -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.ExecutionReport.Rejected -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.ExecutionReport.Expired -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.ExecutionReport.Amended -> copy(clOrdId = newClOrdId)
    is OrderStateEvent.ExecutionReport.Restated -> copy(clOrdId = newClOrdId)
}
