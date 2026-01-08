# Plan v2: Bot Logging Visibility & Order Tracking Fixes

## Executive Summary

This plan addresses:
1. **Invisible bot logs** - Harness subprocess logs not visible
2. **Order tracking bugs** - Cancel/amend events not published, causing "going dark"
3. **Missing strategy logging** - No visibility into decision-making
4. **Slow requote throttle** - 2-second delay is too slow for trading

## Critical Design Decision: Event-Driven vs Feed-Driven Order Tracking

**Problem**: The original plan proposed diffing the `open_orders` WebSocket feed to detect cancels/amends. This is fragile because:
1. Can't distinguish filled orders from canceled orders (both disappear)
2. Race conditions between `fills` and `open_orders` feeds
3. Depends on Kraken sending full snapshots (may send differential updates)

**Solution**: Publish events **directly from command handlers** when REST calls succeed:
- Deterministic - we know exactly what happened
- No race conditions - event published immediately
- No ambiguity - cancel is cancel, amend is amend

The `open_orders` feed is used only for:
- Initial sync on connect
- Detecting external changes (manual orders, recovery)
- Reconciliation/logging (not authoritative)

---

## Changes Required

### 1. Stream Bot Logs Live (Harness)

**File:** `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt`

Replace temp file redirection with real-time streaming:

```kotlin
private fun runBot(worktreePath: Path): BotResult {
    logger.info { "Running bot for ${config.epochTradeCount} trades (max ${config.epochMaxDurationMs}ms)" }

    val args = listOf(
        "bazel", "run", "//bot", "--",
        "--epoch-trades=${config.epochTradeCount}",
        "--epoch-max-duration=${config.epochMaxDurationMs}",
        "--strategy=${config.strategyClass}",
    )
    
    val processBuilder = ProcessBuilder(args)
        .directory(worktreePath.toFile())
        .redirectErrorStream(true)
    
    config.krakenApiKey?.let { processBuilder.environment()["KRAKEN_API_KEY"] = it }
    config.krakenApiSecret?.let { processBuilder.environment()["KRAKEN_API_SECRET"] = it }

    val process = processBuilder.start()
    val outputCapture = StringBuilder()
    
    // Stream output in real-time while capturing for crash analysis
    val outputThread = Thread {
        process.inputStream.bufferedReader().forEachLine { line ->
            logger.info { "[BOT] $line" }  // Use logger, not println
            outputCapture.appendLine(line)
        }
    }.apply { 
        isDaemon = true
        name = "bot-output-reader"
        start() 
    }

    val timeoutMs = config.epochMaxDurationMs + config.gracePeriodMs
    val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

    if (!completed) {
        logger.warn { "Bot did not exit within timeout, killing process" }
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
    }
    
    outputThread.join(2000)  // Wait for output thread to finish draining

    val exitCode = process.exitValue()
    logger.info { "Bot exited with code: $exitCode" }

    return if (exitCode != 0) {
        val error = extractCrashError(outputCapture.toString())
        BotResult(crashed = true, exitCode = exitCode, error = error)
    } else {
        BotResult(crashed = false, exitCode = exitCode)
    }
}
```

**Key changes from v1:**
- Use `logger.info` instead of `println` for consistent formatting
- Use `forEachLine` instead of `useLines` (handles stream closure better)
- Named thread for debugging
- Longer join timeout (2000ms)

---

### 2. Fix Cancel Event Publishing

**File:** `kraken-client/src/main/kotlin/com/didrikquant/kraken/KrakenPrivateWs.kt`

```kotlin
private suspend fun cancelOrder(cmd: Command.CancelOrder) {
    val response = restClient.cancelOrder(cmd.orderId)
    val result = response["result"]?.jsonPrimitive?.contentOrNull
    if (result == "success") {
        logger.info { "Order canceled: ${cmd.orderId}" }
        publishEvent(
            Event.OrderCanceled(
                orderId = cmd.orderId,
                clOrdId = "",  // Not available here
                reason = "user_requested",
                timestamp = System.currentTimeMillis(),
            )
        )
    } else {
        val error = response["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
        logger.error { "Cancel failed for ${cmd.orderId}: $error" }
        // Note: Do NOT throw here - the order might have been filled already
        // The strategy will see it's gone on next iteration
    }
}
```

---

### 3. Add `Event.OrderAmended` and Fix Amend Handler

**File:** `core/src/main/kotlin/com/didrikquant/core/Event.kt`

Add new event type:
```kotlin
public data class OrderAmended(
    val oldOrderId: String,
    val newOrderId: String,  // May be same as oldOrderId, or different if Kraken replaced
    val clOrdId: String,
    val newPrice: BigDecimal,
    val newQty: BigDecimal?,
    val timestamp: Long,
) : Event()
```

**File:** `kraken-client/src/main/kotlin/com/didrikquant/kraken/KrakenPrivateWs.kt`

```kotlin
private suspend fun amendOrder(cmd: Command.AmendOrder) {
    val response = restClient.editOrder(
        orderId = cmd.orderId,
        limitPrice = cmd.newPrice.toDouble(),
        size = cmd.newQty?.toDouble(),
    )
    val result = response["result"]?.jsonPrimitive?.contentOrNull
    if (result == "success") {
        val editStatus = response["editStatus"]?.jsonObject
        val newOrderId = editStatus?.get("orderId")?.jsonPrimitive?.contentOrNull ?: cmd.orderId
        
        logger.info { "Order amended: ${cmd.orderId} -> $newOrderId, price=${cmd.newPrice}" }
        
        publishEvent(
            Event.OrderAmended(
                oldOrderId = cmd.orderId,
                newOrderId = newOrderId,
                clOrdId = "",  // Not available here
                newPrice = cmd.newPrice,
                newQty = cmd.newQty,
                timestamp = System.currentTimeMillis(),
            )
        )
    } else {
        val error = response["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
        logger.error { "Amend failed for ${cmd.orderId}: $error" }
    }
}
```

---

### 4. Handle `OrderAmended` in OrderManager

**File:** `execution/src/main/kotlin/com/didrikquant/execution/OrderManager.kt`

```kotlin
public fun onOrderAmended(event: Event.OrderAmended) {
    // Remove old order (might be under old orderId)
    val existing = orders.remove(event.oldOrderId)
    if (existing == null) {
        logger.warn { "Amend event for unknown order: ${event.oldOrderId}" }
        return
    }
    
    // Create updated order
    val updated = existing.copy(
        orderId = event.newOrderId,
        price = event.newPrice,
        originalQty = event.newQty ?: existing.originalQty,
    )
    
    // Store under new orderId (might be same as old)
    orders[event.newOrderId] = updated
    
    // Update clOrdId mapping if orderId changed
    if (event.oldOrderId != event.newOrderId) {
        clOrdIdToOrderId[existing.clOrdId] = event.newOrderId
    }
    
    logger.debug { "Order amended: ${event.oldOrderId} -> ${event.newOrderId} @ ${event.newPrice}" }
}
```

---

### 5. Handle `OrderAmended` in ExecutionUpdateHandler

**File:** `bot/src/main/kotlin/com/didrikquant/bot/handlers/ExecutionUpdateHandler.kt`

Add case for OrderAmended:
```kotlin
override fun onEvent(event: MutableEvent, sequence: Long, endOfBatch: Boolean) {
    // ... existing code ...
    
    when (val e = event.event) {
        is Event.OrderAccepted -> { /* existing */ }
        is Event.OrderFill -> { /* existing */ }
        is Event.OrderCanceled -> { /* existing */ }
        is Event.OrderRejected -> { /* existing */ }
        is Event.OrderAmended -> {
            orderManager.onOrderAmended(e)
            logger.info { "Order amended: ${e.oldOrderId} -> ${e.newOrderId} @ ${e.newPrice}" }
        }
        else -> {}
    }
}
```

---

### 6. Remove Requote Throttle

**File:** `bot/src/main/kotlin/com/didrikquant/bot/BotConfig.kt`

```kotlin
val requoteIntervalMs: Long = 0,  // Quote on every book update
```

**Rationale:** OrderManager tracks state. Strategy returns empty when no action needed. No artificial throttle required.

---

### 7. Add Strategy Decision Logging

**File:** `strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt`

Add at top:
```kotlin
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
```

Add logging at end of `onBookSnapshot()`:
```kotlin
// After calculating actions
if (actions.isNotEmpty() || openOrders.isEmpty()) {
    logger.info { 
        "mid=$mid spread=${book.spreadBps}bps pos=$position | " +
        "bid=$bidPrice x $bidSize, ask=$askPrice x $askSize | " +
        "orders=${openOrders.size} actions=${actions.size}"
    }
}
```

Add logging in `manageOrder()`:
```kotlin
private fun manageOrder(...): List<StrategyAction> {
    if (existing == null) {
        logger.debug { "$side: placing $targetPrice x $targetSize" }
        return listOf(StrategyAction.Place(...))
    }

    if (isOrderCrossed(existing, mid)) {
        logger.info { "$side: CANCEL crossed order ${existing.price} vs mid $mid" }
        return listOf(StrategyAction.Cancel(existing.orderId))
    }

    if (existing.isPartiallyFilled()) {
        logger.debug { "$side: holding partially filled order" }
        return emptyList()
    }

    val driftBps = priceDriftBps(existing.price, targetPrice, mid)
    if (driftBps > amendThresholdBps) {
        logger.info { "$side: AMEND ${existing.price} -> $targetPrice (${driftBps}bps drift)" }
        return listOf(StrategyAction.Amend(existing.orderId, targetPrice))
    }

    return emptyList()  // Order is stable
}
```

---

### 8. Update Agent Prompt

**File:** `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt`

Add to `buildAgentPrompt()`:

```kotlin
"""
LOGGING REQUIREMENTS (MANDATORY):
Your strategy MUST include logging for observability. Use mu.KotlinLogging:

```kotlin
import mu.KotlinLogging
private val logger = KotlinLogging.logger {}
```

Required log points:
- INFO: Every quote decision (prices, sizes, position)
- INFO: Every amend/cancel with reason
- DEBUG: Order management decisions
- WARN: Unusual conditions (wide spreads, risk limits)

Example:
```kotlin
logger.info { "mid=${'$'}mid pos=${'$'}position | bid=${'$'}bidPrice ask=${'$'}askPrice" }
logger.info { "BUY: AMEND ${'$'}{old} -> ${'$'}{new} (${'$'}{drift}bps drift)" }
logger.warn { "Spread ${'$'}{spread}bps exceeds threshold" }
```

Strategies without logging are incomplete.
"""
```

---

### 9. Reduce Monitoring Frequency (Optional)

**File:** `bot/src/main/kotlin/com/didrikquant/bot/Pipeline.kt`

```kotlin
val monitoringHandler = MonitoringHandler(logEveryNEvents = 100)
```

---

## Implementation Order

| Priority | Change | Why |
|----------|--------|-----|
| 1 | Stream bot logs | Enable visibility before other changes |
| 2 | Fix cancel event | Critical bug - orders stuck in memory |
| 3 | Add OrderAmended event | Critical bug - amends not tracked |
| 4 | Handle OrderAmended in OrderManager | Required for #3 |
| 5 | Handle OrderAmended in ExecutionUpdateHandler | Required for #3 |
| 6 | Remove requote throttle | Enable responsive quoting |
| 7 | Add strategy logging | Observability |
| 8 | Update agent prompt | Future strategies |
| 9 | Reduce monitoring frequency | Nice to have |

---

## Files Modified

| File | Changes |
|------|---------|
| `harness/.../Harness.kt` | Stream logs + update prompt |
| `core/.../Event.kt` | Add OrderAmended event |
| `kraken-client/.../KrakenPrivateWs.kt` | Fix cancel + amend handlers |
| `execution/.../OrderManager.kt` | Add onOrderAmended |
| `bot/.../ExecutionUpdateHandler.kt` | Handle OrderAmended |
| `bot/.../BotConfig.kt` | Remove throttle |
| `strategy/.../AgentXrpStrategy.kt` | Add logging |
| `bot/.../Pipeline.kt` | Reduce monitoring frequency |

---

## Testing Checklist

After implementation:
- [ ] Run harness, verify `[BOT]` logs appear in real-time
- [ ] Place an order, verify OrderAccepted logged
- [ ] Amend an order, verify OrderAmended logged and price updates in strategy view
- [ ] Cancel an order, verify OrderCanceled logged and order removed from strategy view
- [ ] Fill an order, verify OrderFill logged (no spurious OrderCanceled)
- [ ] Strategy logs show quote decisions on every book update

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| High log volume | Acceptable for visibility; tune levels later |
| REST cancel fails for filled order | Don't throw; order will be removed by fill event |
| Kraken editOrder changes orderId | OrderAmended event handles this case |
| Strategy removes logging | Prompt mandates it; baseline logging as example |

---

## What This Plan Does NOT Do

1. **Diff open_orders feed** - Too fragile, causes filled/canceled ambiguity
2. **Track external orders** - Only our orders matter for now
3. **Reconciliation** - Can add later for robustness

These can be added as future enhancements after core bugs are fixed.

---

## Additional: Replay Module Updates for OrderAmended

**File:** `replay/src/main/kotlin/com/didrikquant/replay/storage/RecordedEvent.kt`

### Add to `extractTimestamp()`:
```kotlin
is Event.OrderAmended -> timestamp
```

### Add fields to EventDto:
```kotlin
val oldOrderId: String? = null,
val newOrderId: String? = null,
val newPrice: String? = null,
val newQty: String? = null,
```

### Add to `toEvent()`:
```kotlin
"OrderAmended" -> Event.OrderAmended(
    oldOrderId = oldOrderId!!,
    newOrderId = newOrderId!!,
    clOrdId = clOrdId ?: "",
    newPrice = BigDecimal(newPrice!!),
    newQty = newQty?.let { BigDecimal(it) },
    timestamp = timestamp!!,
)
```

### Add to `fromEvent()`:
```kotlin
is Event.OrderAmended -> EventDto(
    type = "OrderAmended",
    oldOrderId = event.oldOrderId,
    newOrderId = event.newOrderId,
    clOrdId = event.clOrdId,
    newPrice = event.newPrice.toPlainString(),
    newQty = event.newQty?.toPlainString(),
    timestamp = event.timestamp,
)
```

---

**File:** `replay/src/main/kotlin/com/didrikquant/replay/metrics/ReplayMetrics.kt`

Add counter:
```kotlin
private val orderAmendedCount = AtomicLong(0)

// In processEvent():
is Event.OrderAmended -> orderAmendedCount.incrementAndGet()
```
