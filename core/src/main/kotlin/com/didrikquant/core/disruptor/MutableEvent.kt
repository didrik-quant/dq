package com.didrikquant.core.disruptor

import com.didrikquant.core.Event
import com.didrikquant.core.Command

public class MutableEvent {
    @Volatile
    public var event: Event? = null

    @Volatile
    public var commands: List<Command> = emptyList()

    public fun clear() {
        event = null
        commands = emptyList()
    }
}
