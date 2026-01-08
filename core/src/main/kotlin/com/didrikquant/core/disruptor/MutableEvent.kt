package com.didrikquant.core.disruptor

import com.didrikquant.core.Command
import com.didrikquant.core.Event
import com.didrikquant.core.StrategyAction

public class MutableEvent {
    @Volatile
    public var event: Event? = null

    @Volatile
    public var actions: List<StrategyAction> = emptyList()

    @Volatile
    public var commands: List<Command> = emptyList()

    public fun clear() {
        event = null
        actions = emptyList()
        commands = emptyList()
    }
}
