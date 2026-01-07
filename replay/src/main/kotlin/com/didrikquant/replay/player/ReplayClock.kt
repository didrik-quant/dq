package com.didrikquant.replay.player

import java.util.concurrent.atomic.AtomicLong

public class ReplayClock {

    private val currentTimeMs = AtomicLong(0)

    public fun advanceTo(timestampMs: Long) {
        currentTimeMs.set(timestampMs)
    }

    public fun currentTimeMillis(): Long = currentTimeMs.get()

    public fun reset() {
        currentTimeMs.set(0)
    }
}
