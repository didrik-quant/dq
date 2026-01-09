package com.didrikquant.core.disruptor

import com.didrikquant.core.Command
import com.didrikquant.core.Event
import com.didrikquant.core.OrderBookSnapshot
import com.didrikquant.core.PositionSnapshot
import com.didrikquant.orderstate.OrderSnapshot
import com.didrikquant.orderstate.OrderStateEvent

public class MutableEvent {
    @Volatile
    public var event: Event? = null

    @Volatile
    public var orderEvent: OrderStateEvent? = null

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
        orderEvent = null
        orderBookSnapshot = null
        openOrders = emptyList()
        positionSnapshot = null
        commands = emptyList()
    }
}
