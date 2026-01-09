package com.didrikquant.core.disruptor

import com.didrikquant.core.Command
import com.didrikquant.core.Event
import com.didrikquant.core.OrderBookSnapshot
import com.didrikquant.core.PositionSnapshot
import com.didrikquant.orderstate.OrderSnapshot

public class MutableEvent {
    @Volatile
    public var event: Event? = null

    @Volatile
    public var orderBookSnapshot: OrderBookSnapshot? = null

    @Volatile
    public var openOrders: List<OrderSnapshot> = emptyList()

    @Volatile
    public var positionSnapshot: PositionSnapshot? = null

    @Volatile
    public var commands: List<Command> = emptyList()

    public fun clear() {
        event = null
        orderBookSnapshot = null
        openOrders = emptyList()
        positionSnapshot = null
        commands = emptyList()
    }
}
