package com.didrikquant.risk

import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

public class KillSwitch(private val config: RiskConfig) {
    private val triggered = AtomicBoolean(false)
    private val reason = AtomicReference<String?>(null)
    private val totalPnl = AtomicReference(BigDecimal.ZERO)

    public fun isTriggered(): Boolean = triggered.get()

    public fun getTriggerReason(): String? = reason.get()

    public fun manualTrigger(triggerReason: String) {
        if (triggered.compareAndSet(false, true)) {
            reason.set(triggerReason)
        }
    }

    public fun updatePnl(pnl: BigDecimal) {
        totalPnl.set(pnl)
        if (pnl < config.maxLossUsd.negate()) {
            if (triggered.compareAndSet(false, true)) {
                reason.set("Max loss exceeded: PnL = $pnl, limit = -${config.maxLossUsd}")
            }
        }
    }

    public fun reset() {
        triggered.set(false)
        reason.set(null)
    }
}
