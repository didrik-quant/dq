package com.didrikquant.execution

import com.didrikquant.execution.ApplyResult.*
import com.didrikquant.orderstate.OrderSnapshot
import com.didrikquant.orderstate.OrderStateEvent
import com.didrikquant.orderstate.TransitionResult

public class OrderStore {
    private val orders = mutableMapOf<String, OrderSnapshot>()

    public fun create(instruction: OrderStateEvent.Instruction.Create): CreateResult {
        val existing = orders[instruction.clOrdId]
        if (existing != null) {
            return CreateResult.DuplicateClOrdId(existing)
        }
        val snapshot = OrderSnapshot.fromInstruction(instruction)
        orders[instruction.clOrdId] = snapshot
        return CreateResult.Created(snapshot)
    }

    public fun apply(event: OrderStateEvent): ApplyResult {
        val clOrdId = event.clOrdId
        val before = orders[clOrdId] ?: return OrderNotFound(clOrdId)

        return when (val result = before.apply(event)) {
            is TransitionResult.Success -> {
                orders[clOrdId] = result.snapshot
                Success(before, result.snapshot)
            }

            is TransitionResult.SuccessWithWarning -> {
                orders[clOrdId] = result.snapshot
                SuccessWithWarning(before, result.snapshot, result.warning)
            }

            is TransitionResult.Unchanged -> {
                Success(before, before)
            }

            is TransitionResult.Duplicate -> {
                DuplicateExecution(before, result.execId)
            }

            is TransitionResult.Invalid -> {
                InvalidTransition(before, result.reason)
            }

            is TransitionResult.UnchangedTerminal -> {
                Success(before, before)
            }
        }
    }

    public fun get(clOrdId: String): OrderSnapshot? = orders[clOrdId]

    public fun getByOrderId(orderId: String): OrderSnapshot? =
        orders.values.find { it.orderId == orderId }

    public fun activeOrders(): List<OrderSnapshot> =
        orders.values.filter { it.isActive }

    public fun allOrders(): List<OrderSnapshot> =
        orders.values.toList()

    public fun size(): Int = orders.size
}
