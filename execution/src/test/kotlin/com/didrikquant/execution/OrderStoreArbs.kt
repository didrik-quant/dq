package com.didrikquant.execution

import com.didrikquant.orderstate.OrderState
import com.didrikquant.orderstate.OrderStateEvent
import com.didrikquant.orderstate.Side
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import java.math.BigDecimal
import java.math.RoundingMode

internal object OrderStoreArbs {

    private val alphanumericChars = ('a'..'z') + ('0'..'9')

    internal fun clOrdId(): Arb<String> = arbitrary {
        buildString {
            repeat(18) { append(alphanumericChars.random()) }
        }
    }

    internal fun orderId(): Arb<String> = arbitrary {
        buildString {
            repeat(24) { append(alphanumericChars.random()) }
        }
    }

    internal fun execId(): Arb<String> = arbitrary {
        buildString {
            repeat(24) { append(alphanumericChars.random()) }
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
    )

    internal fun createInstruction(): Arb<OrderStateEvent.Instruction.Create> = arbitrary {
        OrderStateEvent.Instruction.Create(
            clOrdId = clOrdId().bind(),
            side = side().bind(),
            price = price().bind(),
            qty = qty().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun uniqueCreateInstructions(count: Int): Arb<List<OrderStateEvent.Instruction.Create>> = arbitrary {
        val instructions = mutableListOf<OrderStateEvent.Instruction.Create>()
        val usedClOrdIds = mutableSetOf<String>()
        repeat(count) {
            var instruction: OrderStateEvent.Instruction.Create
            do {
                instruction = createInstruction().bind()
            } while (instruction.clOrdId in usedClOrdIds)
            usedClOrdIds.add(instruction.clOrdId)
            instructions.add(instruction)
        }
        instructions.toList()
    }

    internal fun acceptedEvent(
        clOrdId: String,
        side: Side,
        price: BigDecimal,
        qty: BigDecimal,
    ): Arb<OrderStateEvent.ExecutionReport.Accepted> = arbitrary {
        OrderStateEvent.ExecutionReport.Accepted(
            clOrdId = clOrdId,
            orderId = orderId().bind(),
            side = side,
            price = price,
            qty = qty,
            timestamp = timestamp().bind(),
        )
    }

    internal fun partialFillEvent(
        clOrdId: String,
        orderId: String,
        fillQty: BigDecimal,
        cumQty: BigDecimal,
        leavesQty: BigDecimal,
    ): Arb<OrderStateEvent.ExecutionReport.PartialFill> = arbitrary {
        OrderStateEvent.ExecutionReport.PartialFill(
            clOrdId = clOrdId,
            orderId = orderId,
            execId = execId().bind(),
            fillQty = fillQty,
            fillPrice = price().bind(),
            cumQty = cumQty,
            leavesQty = leavesQty,
            timestamp = timestamp().bind(),
        )
    }

    internal fun filledEvent(
        clOrdId: String,
        orderId: String,
        fillQty: BigDecimal,
        cumQty: BigDecimal,
    ): Arb<OrderStateEvent.ExecutionReport.Filled> = arbitrary {
        OrderStateEvent.ExecutionReport.Filled(
            clOrdId = clOrdId,
            orderId = orderId,
            execId = execId().bind(),
            fillQty = fillQty,
            fillPrice = price().bind(),
            cumQty = cumQty,
            timestamp = timestamp().bind(),
        )
    }

    internal fun canceledEvent(
        clOrdId: String,
        orderId: String,
    ): Arb<OrderStateEvent.ExecutionReport.Canceled> = arbitrary {
        OrderStateEvent.ExecutionReport.Canceled(
            clOrdId = clOrdId,
            orderId = orderId,
            reason = reason().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal fun rejectedEvent(clOrdId: String): Arb<OrderStateEvent.ExecutionReport.Rejected> = arbitrary {
        OrderStateEvent.ExecutionReport.Rejected.beforeAcceptance(
            clOrdId = clOrdId,
            reason = reason().bind(),
            timestamp = timestamp().bind(),
        )
    }

    internal data class OrderLifecycle(
        val create: OrderStateEvent.Instruction.Create,
        val events: List<OrderStateEvent>,
        val finalState: OrderState,
    )

    internal fun completeOrderLifecycle(): Arb<OrderLifecycle> = arbitrary {
        val create = createInstruction().bind()
        val events = mutableListOf<OrderStateEvent>()
        var ts = create.timestamp + Arb.long(1L..1000L).bind()

        val path = Arb.int(0..4).bind()

        when (path) {
            0 -> {
                val rejected = OrderStateEvent.ExecutionReport.Rejected.beforeAcceptance(
                    clOrdId = create.clOrdId,
                    reason = reason().bind(),
                    timestamp = ts,
                )
                events.add(rejected)
                OrderLifecycle(create, events, OrderState.REJECTED)
            }
            1 -> {
                val orderId = orderId().bind()
                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = create.clOrdId,
                    orderId = orderId,
                    side = create.side,
                    price = create.price,
                    qty = create.qty,
                    timestamp = ts,
                )
                events.add(accepted)
                ts += Arb.long(1L..1000L).bind()

                val canceled = OrderStateEvent.ExecutionReport.Canceled(
                    clOrdId = create.clOrdId,
                    orderId = orderId,
                    reason = reason().bind(),
                    timestamp = ts,
                )
                events.add(canceled)
                OrderLifecycle(create, events, OrderState.CANCELED)
            }
            2 -> {
                val orderId = orderId().bind()
                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = create.clOrdId,
                    orderId = orderId,
                    side = create.side,
                    price = create.price,
                    qty = create.qty,
                    timestamp = ts,
                )
                events.add(accepted)
                ts += Arb.long(1L..1000L).bind()

                val filled = OrderStateEvent.ExecutionReport.Filled(
                    clOrdId = create.clOrdId,
                    orderId = orderId,
                    execId = execId().bind(),
                    fillQty = create.qty,
                    fillPrice = price().bind(),
                    cumQty = create.qty,
                    timestamp = ts,
                )
                events.add(filled)
                OrderLifecycle(create, events, OrderState.FILLED)
            }
            3 -> {
                val orderId = orderId().bind()
                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = create.clOrdId,
                    orderId = orderId,
                    side = create.side,
                    price = create.price,
                    qty = create.qty,
                    timestamp = ts,
                )
                events.add(accepted)
                ts += Arb.long(1L..1000L).bind()

                val fillQty = create.qty.divide(BigDecimal(2), 8, RoundingMode.DOWN)
                val partial = OrderStateEvent.ExecutionReport.PartialFill(
                    clOrdId = create.clOrdId,
                    orderId = orderId,
                    execId = execId().bind(),
                    fillQty = fillQty,
                    fillPrice = price().bind(),
                    cumQty = fillQty,
                    leavesQty = create.qty - fillQty,
                    timestamp = ts,
                )
                events.add(partial)
                ts += Arb.long(1L..1000L).bind()

                val remaining = create.qty - fillQty
                val filled = OrderStateEvent.ExecutionReport.Filled(
                    clOrdId = create.clOrdId,
                    orderId = orderId,
                    execId = execId().bind(),
                    fillQty = remaining,
                    fillPrice = price().bind(),
                    cumQty = create.qty,
                    timestamp = ts,
                )
                events.add(filled)
                OrderLifecycle(create, events, OrderState.FILLED)
            }
            else -> {
                val orderId = orderId().bind()
                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = create.clOrdId,
                    orderId = orderId,
                    side = create.side,
                    price = create.price,
                    qty = create.qty,
                    timestamp = ts,
                )
                events.add(accepted)
                OrderLifecycle(create, events, OrderState.OPEN)
            }
        }
    }

    internal fun multipleOrderLifecycles(count: Int): Arb<List<OrderLifecycle>> = arbitrary {
        val lifecycles = mutableListOf<OrderLifecycle>()
        val usedClOrdIds = mutableSetOf<String>()

        repeat(count) {
            var lifecycle: OrderLifecycle
            do {
                lifecycle = completeOrderLifecycle().bind()
            } while (lifecycle.create.clOrdId in usedClOrdIds)
            usedClOrdIds.add(lifecycle.create.clOrdId)
            lifecycles.add(lifecycle)
        }
        lifecycles.toList()
    }

    internal data class InterleavedEvents(
        val creates: List<OrderStateEvent.Instruction.Create>,
        val events: List<OrderStateEvent>,
    )

    internal fun interleavedOrderEvents(orderCount: Int): Arb<InterleavedEvents> = arbitrary {
        val lifecycles = multipleOrderLifecycles(orderCount).bind()
        val allEvents = mutableListOf<OrderStateEvent>()

        lifecycles.forEach { lifecycle ->
            allEvents.addAll(lifecycle.events)
        }

        allEvents.shuffle()

        InterleavedEvents(
            creates = lifecycles.map { it.create },
            events = allEvents,
        )
    }
}
