package com.didrikquant.execution

import com.didrikquant.orderstate.OrderState
import com.didrikquant.orderstate.OrderStateEvent
import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.math.RoundingMode

@OptIn(ExperimentalKotest::class)
private val config = PropTestConfig(iterations = 100)

internal class OrderStorePropertyTests : FunSpec({

    context("creation properties") {

        test("created order is retrievable via get") {
            checkAll(OrderStoreArbs.createInstruction()) { instruction ->
                val store = OrderStore()
                val result = store.create(instruction)

                result.shouldBeInstanceOf<CreateResult.Created>()
                store.get(instruction.clOrdId) shouldBe (result as CreateResult.Created).snapshot
            }
        }

        test("duplicate clOrdId returns DuplicateClOrdId with existing snapshot") {
            checkAll(OrderStoreArbs.createInstruction()) { instruction ->
                val store = OrderStore()
                val first = store.create(instruction)
                first.shouldBeInstanceOf<CreateResult.Created>()

                val second = store.create(instruction)
                second.shouldBeInstanceOf<CreateResult.DuplicateClOrdId>()
                (second as CreateResult.DuplicateClOrdId).existing shouldBe
                    (first as CreateResult.Created).snapshot
            }
        }

        test("size increases by 1 after successful create") {
            checkAll(OrderStoreArbs.uniqueCreateInstructions(5)) { instructions ->
                val store = OrderStore()
                instructions.forEachIndexed { index, instruction ->
                    store.size() shouldBe index
                    store.create(instruction)
                    store.size() shouldBe index + 1
                }
            }
        }

        test("allOrders contains created order") {
            checkAll(OrderStoreArbs.createInstruction()) { instruction ->
                val store = OrderStore()
                val result = store.create(instruction) as CreateResult.Created

                store.allOrders() shouldContain result.snapshot
            }
        }

        test("created order starts in PENDING_NEW state") {
            checkAll(OrderStoreArbs.createInstruction()) { instruction ->
                val store = OrderStore()
                val result = store.create(instruction) as CreateResult.Created

                result.snapshot.state shouldBe OrderState.PENDING_NEW
            }
        }
    }

    context("lookup properties") {

        test("after Accepted event getByOrderId returns correct order") {
            checkAll(
                OrderStoreArbs.createInstruction(),
                OrderStoreArbs.orderId(),
                OrderStoreArbs.timestamp(),
            ) { instruction, orderId, ts ->
                val store = OrderStore()
                store.create(instruction)

                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = instruction.clOrdId,
                    orderId = orderId,
                    side = instruction.side,
                    price = instruction.price,
                    qty = instruction.qty,
                    timestamp = ts,
                )

                val result = store.apply(accepted)
                result.shouldBeInstanceOf<ApplyResult.Success>()

                val byClOrdId = store.get(instruction.clOrdId)
                val byOrderId = store.getByOrderId(orderId)

                byClOrdId shouldNotBe null
                byOrderId shouldNotBe null
                byClOrdId shouldBe byOrderId
            }
        }

        test("getByOrderId returns null for unknown orderId") {
            checkAll(
                OrderStoreArbs.createInstruction(),
                OrderStoreArbs.orderId(),
            ) { instruction, unknownOrderId ->
                val store = OrderStore()
                store.create(instruction)

                store.getByOrderId(unknownOrderId) shouldBe null
            }
        }

        test("get returns null for unknown clOrdId") {
            checkAll(OrderStoreArbs.clOrdId()) { unknownClOrdId ->
                val store = OrderStore()
                store.get(unknownClOrdId) shouldBe null
            }
        }
    }

    context("collection invariants") {

        test("activeOrders is subset of allOrders") {
            checkAll(config, OrderStoreArbs.multipleOrderLifecycles(3)) { lifecycles ->
                val store = OrderStore()

                lifecycles.forEach { lifecycle ->
                    store.create(lifecycle.create)
                    lifecycle.events.forEach { event ->
                        store.apply(event)
                    }
                }

                val active = store.activeOrders()
                val all = store.allOrders()

                active.forEach { order ->
                    all shouldContain order
                }
            }
        }

        test("activeOrders contains only orders where isActive is true") {
            checkAll(config, OrderStoreArbs.multipleOrderLifecycles(5)) { lifecycles ->
                val store = OrderStore()

                lifecycles.forEach { lifecycle ->
                    store.create(lifecycle.create)
                    lifecycle.events.forEach { event ->
                        store.apply(event)
                    }
                }

                store.activeOrders().forEach { order ->
                    order.isActive shouldBe true
                }
            }
        }

        test("activeOrders size is less than or equal to allOrders size") {
            checkAll(config, OrderStoreArbs.multipleOrderLifecycles(5)) { lifecycles ->
                val store = OrderStore()

                lifecycles.forEach { lifecycle ->
                    store.create(lifecycle.create)
                    lifecycle.events.forEach { event ->
                        store.apply(event)
                    }
                }

                (store.activeOrders().size <= store.allOrders().size) shouldBe true
            }
        }
    }

    context("order independence") {

        test("event on order A does not modify order B") {
            checkAll(
                OrderStoreArbs.uniqueCreateInstructions(2),
                OrderStoreArbs.orderId(),
                OrderStoreArbs.timestamp(),
            ) { instructions, orderId, ts ->
                val store = OrderStore()
                val (instructionA, instructionB) = instructions

                store.create(instructionA)
                store.create(instructionB)

                val snapshotBBefore = store.get(instructionB.clOrdId)!!

                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = instructionA.clOrdId,
                    orderId = orderId,
                    side = instructionA.side,
                    price = instructionA.price,
                    qty = instructionA.qty,
                    timestamp = ts,
                )

                store.apply(accepted)

                val snapshotBAfter = store.get(instructionB.clOrdId)!!
                snapshotBAfter shouldBe snapshotBBefore
            }
        }
    }

    context("result type properties") {

        test("unknown clOrdId returns OrderNotFound") {
            checkAll(
                OrderStoreArbs.createInstruction(),
                OrderStoreArbs.clOrdId(),
                OrderStoreArbs.orderId(),
                OrderStoreArbs.timestamp(),
            ) { instruction, unknownClOrdId, orderId, ts ->
                if (instruction.clOrdId != unknownClOrdId) {
                    val store = OrderStore()
                    store.create(instruction)

                    val accepted = OrderStateEvent.ExecutionReport.Accepted(
                        clOrdId = unknownClOrdId,
                        orderId = orderId,
                        side = instruction.side,
                        price = instruction.price,
                        qty = instruction.qty,
                        timestamp = ts,
                    )

                    val result = store.apply(accepted)
                    result.shouldBeInstanceOf<ApplyResult.OrderNotFound>()
                    (result as ApplyResult.OrderNotFound).clOrdId shouldBe unknownClOrdId
                }
            }
        }

        test("valid event returns Success with correct before and after snapshots") {
            checkAll(
                OrderStoreArbs.createInstruction(),
                OrderStoreArbs.orderId(),
                OrderStoreArbs.timestamp(),
            ) { instruction, orderId, ts ->
                val store = OrderStore()
                val createResult = store.create(instruction) as CreateResult.Created
                val before = createResult.snapshot

                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = instruction.clOrdId,
                    orderId = orderId,
                    side = instruction.side,
                    price = instruction.price,
                    qty = instruction.qty,
                    timestamp = ts,
                )

                val result = store.apply(accepted)
                result.shouldBeInstanceOf<ApplyResult.Success>()

                val success = result as ApplyResult.Success
                success.before shouldBe before
                success.after.state shouldBe OrderState.OPEN
                success.after.orderId shouldBe orderId
            }
        }

        test("after Success get returns the after snapshot") {
            checkAll(
                OrderStoreArbs.createInstruction(),
                OrderStoreArbs.orderId(),
                OrderStoreArbs.timestamp(),
            ) { instruction, orderId, ts ->
                val store = OrderStore()
                store.create(instruction)

                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = instruction.clOrdId,
                    orderId = orderId,
                    side = instruction.side,
                    price = instruction.price,
                    qty = instruction.qty,
                    timestamp = ts,
                )

                val result = store.apply(accepted) as ApplyResult.Success
                store.get(instruction.clOrdId) shouldBe result.after
            }
        }

        test("event on terminal order returns InvalidTransition") {
            checkAll(
                config,
                OrderStoreArbs.completeOrderLifecycle(),
                OrderStoreArbs.orderId(),
                OrderStoreArbs.timestamp(),
            ) { lifecycle, orderId, ts ->
                if (lifecycle.finalState.isTerminal()) {
                    val store = OrderStore()
                    store.create(lifecycle.create)
                    lifecycle.events.forEach { event ->
                        store.apply(event)
                    }

                    val extraEvent = OrderStateEvent.ExecutionReport.Accepted(
                        clOrdId = lifecycle.create.clOrdId,
                        orderId = orderId,
                        side = lifecycle.create.side,
                        price = lifecycle.create.price,
                        qty = lifecycle.create.qty,
                        timestamp = ts,
                    )

                    val result = store.apply(extraEvent)
                    result.shouldBeInstanceOf<ApplyResult.InvalidTransition>()
                }
            }
        }
    }

    context("terminal state properties") {

        test("terminal orders are not in activeOrders") {
            checkAll(config, OrderStoreArbs.completeOrderLifecycle()) { lifecycle ->
                val store = OrderStore()
                store.create(lifecycle.create)
                lifecycle.events.forEach { event ->
                    store.apply(event)
                }

                val order = store.get(lifecycle.create.clOrdId)!!

                if (order.state.isTerminal()) {
                    store.activeOrders() shouldNotContain order
                }
            }
        }

        test("after terminal transition order is still in allOrders") {
            checkAll(config, OrderStoreArbs.completeOrderLifecycle()) { lifecycle ->
                val store = OrderStore()
                store.create(lifecycle.create)
                lifecycle.events.forEach { event ->
                    store.apply(event)
                }

                val order = store.get(lifecycle.create.clOrdId)!!

                if (order.state.isTerminal()) {
                    store.allOrders() shouldContain order
                }
            }
        }
    }

    context("instruction events") {

        test("instruction events other than Create return Success with unchanged snapshot") {
            checkAll(
                OrderStoreArbs.createInstruction(),
                OrderStoreArbs.timestamp(),
            ) { instruction, ts ->
                val store = OrderStore()
                store.create(instruction)
                val before = store.get(instruction.clOrdId)!!

                val cancelInstruction = OrderStateEvent.Instruction.Cancel(
                    clOrdId = instruction.clOrdId,
                    timestamp = ts,
                )

                val result = store.apply(cancelInstruction)
                result.shouldBeInstanceOf<ApplyResult.Success>()

                val success = result as ApplyResult.Success
                success.before shouldBe before
                success.after shouldBe before
            }
        }
    }

    context("duplicate execution detection") {

        test("duplicate execId returns DuplicateExecution") {
            checkAll(
                OrderStoreArbs.createInstruction(),
                OrderStoreArbs.orderId(),
                OrderStoreArbs.execId(),
                OrderStoreArbs.price(),
                OrderStoreArbs.timestamp(),
            ) { instruction, orderId, execId, fillPrice, ts ->
                val store = OrderStore()
                store.create(instruction)

                val accepted = OrderStateEvent.ExecutionReport.Accepted(
                    clOrdId = instruction.clOrdId,
                    orderId = orderId,
                    side = instruction.side,
                    price = instruction.price,
                    qty = instruction.qty,
                    timestamp = ts,
                )

                store.apply(accepted)

                val fillQty = instruction.qty.divide(BigDecimal(2), 8, RoundingMode.DOWN)
                val fill1 = OrderStateEvent.ExecutionReport.PartialFill(
                    clOrdId = instruction.clOrdId,
                    orderId = orderId,
                    execId = execId,
                    fillQty = fillQty,
                    fillPrice = fillPrice,
                    cumQty = fillQty,
                    leavesQty = instruction.qty - fillQty,
                    timestamp = ts + 1000,
                )

                val result1 = store.apply(fill1)
                result1.shouldBeInstanceOf<ApplyResult.Success>()

                val fill2 = fill1.copy(timestamp = ts + 2000)
                val result2 = store.apply(fill2)
                result2.shouldBeInstanceOf<ApplyResult.DuplicateExecution>()
                (result2 as ApplyResult.DuplicateExecution).execId shouldBe execId
            }
        }
    }

    context("multi-order scenarios") {

        test("multiple independent orders can be tracked simultaneously") {
            checkAll(config, OrderStoreArbs.uniqueCreateInstructions(5)) { instructions ->
                val store = OrderStore()

                instructions.forEach { instruction ->
                    val result = store.create(instruction)
                    result.shouldBeInstanceOf<CreateResult.Created>()
                }

                store.size() shouldBe instructions.size
                store.allOrders() shouldHaveSize instructions.size

                instructions.forEach { instruction ->
                    store.get(instruction.clOrdId) shouldNotBe null
                }
            }
        }

        test("interleaved events from multiple orders are handled correctly") {
            checkAll(config, OrderStoreArbs.interleavedOrderEvents(3)) { interleaved ->
                val store = OrderStore()

                interleaved.creates.forEach { create ->
                    store.create(create)
                }

                interleaved.events.forEach { event ->
                    val result = store.apply(event)
                    val isValid = result is ApplyResult.Success ||
                        result is ApplyResult.SuccessWithWarning ||
                        result is ApplyResult.InvalidTransition

                    isValid shouldBe true
                }

                store.size() shouldBe interleaved.creates.size
            }
        }
    }
})
