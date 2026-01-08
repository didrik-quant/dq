package com.didrikquant.core.disruptor

import com.didrikquant.core.Command
import com.didrikquant.core.Event
import com.didrikquant.core.ExecutionSnapshot
import com.didrikquant.core.OrderBookSnapshot
import com.didrikquant.core.PendingOrderIntent
import com.didrikquant.core.StrategyAction

public class MutableEvent {
    // Input event
    @Volatile
    public var event: Event? = null

    // Snapshots (set by state-owning handlers)
    @Volatile
    public var orderBookSnapshot: OrderBookSnapshot? = null

    @Volatile
    public var executionSnapshot: ExecutionSnapshot? = null

    // Strategy -> Risk
    @Volatile
    public var actions: List<StrategyAction> = emptyList()

    // Risk -> Output
    @Volatile
    public var commands: List<Command> = emptyList()

    // Risk -> ExecutionUpdate (pending orders to register)
    @Volatile
    public var newPendingOrders: List<PendingOrderIntent> = emptyList()

    public fun clear() {
        event = null
        orderBookSnapshot = null
        executionSnapshot = null
        actions = emptyList()
        commands = emptyList()
        newPendingOrders = emptyList()
    }
}
