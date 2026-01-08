# Plan: Bot Logging Visibility & Strategy Decision Logging

## Problem Statement

1. **Bot logs are invisible** - Harness redirects bot stdout to a temp file that gets deleted
2. **2-second requote throttle** - Way too slow for trading, causes strategy to appear "dark"
3. **No strategy decision logging** - AgentXrpStrategy has zero logging, no visibility into decisions
4. **Agent prompt doesn't require logging** - Evolved strategies have no observability

## Changes Required

### 1. Stream Bot Logs Live to Harness Console

**File:** `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt`

**Current (lines 182-201):**
```kotlin
val logFile = Files.createTempFile("bot-output", ".log")
val processBuilder = ProcessBuilder(args)
    .directory(worktreePath.toFile())
    .redirectErrorStream(true)
    .redirectOutput(logFile.toFile())  // Goes to temp file - invisible!
```

**Change to:**
- Remove temp file redirection
- Use `ProcessBuilder.inheritIO()` for stderr 
- Create a thread to read stdout and forward to logger with `[BOT]` prefix
- Capture output in a StringBuilder for crash analysis

**New approach:**
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
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                println("[BOT] $line")  // Real-time output
                outputCapture.appendLine(line)
            }
        }
    }.apply { 
        isDaemon = true
        start() 
    }

    val timeoutMs = config.epochMaxDurationMs + config.gracePeriodMs
    val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

    if (!completed) {
        logger.warn { "Bot did not exit within timeout, killing process" }
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
    }
    
    outputThread.join(1000)  // Wait for output thread to finish

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

---

### 2. Remove/Reduce Requote Throttle

**File:** `bot/src/main/kotlin/com/didrikquant/bot/BotConfig.kt`

**Current (line 13):**
```kotlin
val requoteIntervalMs: Long = 2000,  // 2 seconds - way too slow!
```

**Change to:**
```kotlin
val requoteIntervalMs: Long = 0,  // Quote on every book update
```

**Rationale:** The OrderManager already tracks order state. The strategy's `manageOrder()` function returns empty list if orders don't need changes. No need for artificial throttling.

**Alternative:** If some throttle is desired for rate limiting, use 10-50ms max.

---

### 3. Add Baseline Logging to AgentXrpStrategy

**File:** `strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt`

**Add at top of file:**
```kotlin
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
```

**Add logging in `onBookSnapshot()`:**
```kotlin
override fun onBookSnapshot(
    book: OrderBookSnapshot,
    position: BigDecimal,
    openOrders: List<TrackedOrder>,
): List<StrategyAction> {
    if (!book.isValid()) {
        logger.debug { "Book invalid - skipping" }
        return emptyList()
    }

    val mid = book.midPrice ?: run {
        logger.debug { "No mid price - skipping" }
        return emptyList()
    }

    // ... existing spread/price calculations ...

    val actions = mutableListOf<StrategyAction>()
    val existingBid = openOrders.find { it.side == Side.BUY }
    val existingAsk = openOrders.find { it.side == Side.SELL }

    actions.addAll(manageOrder(existingBid, Side.BUY, bidPrice, bidSize, mid))
    actions.addAll(manageOrder(existingAsk, Side.SELL, askPrice, askSize, mid))

    // Log decision summary
    logger.info { 
        "mid=$mid pos=$position | " +
        "bid=${bidPrice}x${bidSize} ask=${askPrice}x${askSize} | " +
        "actions=${actions.size} orders=${openOrders.size}"
    }

    return actions
}
```

**Add logging in `manageOrder()`:**
```kotlin
private fun manageOrder(
    existing: TrackedOrder?,
    side: Side,
    targetPrice: BigDecimal,
    targetSize: BigDecimal,
    mid: BigDecimal,
): List<StrategyAction> {
    if (existing == null) {
        logger.debug { "$side: no order, placing at $targetPrice x $targetSize" }
        return listOf(StrategyAction.Place(OrderIntent(side, targetPrice, targetSize)))
    }

    if (isOrderCrossed(existing, mid)) {
        logger.debug { "$side: order crossed (${existing.price} vs mid $mid), canceling" }
        return listOf(StrategyAction.Cancel(existing.orderId))
    }

    if (existing.isPartiallyFilled()) {
        logger.debug { "$side: partially filled, holding" }
        return emptyList()
    }

    val driftBps = priceDriftBps(existing.price, targetPrice, mid)
    if (driftBps > amendThresholdBps) {
        logger.debug { "$side: drift ${driftBps}bps > ${amendThresholdBps}bps, amending to $targetPrice" }
        return listOf(StrategyAction.Amend(existing.orderId, targetPrice))
    }

    logger.trace { "$side: order stable at ${existing.price}, drift ${driftBps}bps" }
    return emptyList()
}
```

---

### 4. Update Agent Prompt to Require Logging

**File:** `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt`

**Update `buildAgentPrompt()` (lines 99-147):**

Add a new section to the prompt:

```kotlin
private fun buildAgentPrompt(worktreePath: Path, epoch: Int): String {
    // ... existing code ...

    return """
        You are a trading strategy developer. Your objective is to MAXIMIZE SHARPE RATIO for ${config.instrument}.
        
        CONSTRAINTS:
        - You CANNOT rely on being the fastest. Assume other participants have lower latency.
        - You are NOT required to implement market making. Any strategy that improves Sharpe is valid.
        - The algorithm type doesn't matter—momentum, mean reversion, statistical arbitrage, whatever works.
        
        WORKING DIRECTORY: $worktreePath
        
        IMPORTANT FILES:
        - Strategy code: strategy/src/main/kotlin/com/didrikquant/strategy/${config.strategyClass}.kt
        - Evolution log: agents/${config.instrument}/evolution.md

        LOGGING REQUIREMENTS (MANDATORY):
        Your strategy MUST include logging so the trader can observe decision-making in real-time.
        Use `mu.KotlinLogging`:
        ```kotlin
        import mu.KotlinLogging
        private val logger = KotlinLogging.logger {}
        ```
        
        Required log points:
        - INFO: Every quote decision with prices, sizes, position, and reasoning
        - DEBUG: Why orders are being placed, amended, canceled, or held
        - WARN: Unusual conditions (wide spreads, thin books, risk limits hit)
        
        Example:
        ```kotlin
        logger.info { "mid=${'$'}mid pos=${'$'}position | bid=${'$'}bidPrice ask=${'$'}askPrice | signal=${'$'}signal" }
        logger.debug { "BUY: amending ${'$'}oldPrice -> ${'$'}newPrice (drift ${'$'}driftBps bps)" }
        logger.warn { "Spread ${spreadBps}bps > threshold, widening quotes" }
        ```
        
        Strategies without adequate logging will be considered incomplete.

        Read the evolution log to understand the history of changes and their results.
        $crashContext
        TOOLS:
        - `dq fills` - View fills from last epoch
        - `dq book --at <timestamp>` - View order book at a specific timestamp

        WORKFLOW:
        1. Read the evolution log to understand past changes and results
        2. Make ONE focused improvement to the strategy
        3. Ensure your strategy has adequate logging (see LOGGING REQUIREMENTS above)
        4. Run `bazel build //...` to verify your changes compile
        5. Fix any build errors before finishing

        This is epoch $epoch. Good luck.
        """.trimIndent()
}
```

---

### 5. Reduce MonitoringHandler Frequency (Optional)

**File:** `bot/src/main/kotlin/com/didrikquant/bot/Pipeline.kt`

**Current (line 64):**
```kotlin
val monitoringHandler = MonitoringHandler(logEveryNEvents = 1000)
```

**Change to:**
```kotlin
val monitoringHandler = MonitoringHandler(logEveryNEvents = 100)
```

---

## Implementation Order

1. **Harness log streaming** - Most critical, enables visibility
2. **Remove requote throttle** - Fixes "going dark" issue  
3. **Add baseline strategy logging** - Immediate observability
4. **Update agent prompt** - Future strategies will have logging
5. **Reduce monitoring frequency** - Nice to have

## Files Modified

| File | Change |
|------|--------|
| `harness/.../Harness.kt` | Stream bot logs + update prompt |
| `bot/.../BotConfig.kt` | Remove requote throttle |
| `strategy/.../AgentXrpStrategy.kt` | Add decision logging |
| `bot/.../Pipeline.kt` | Reduce monitoring frequency |

## Testing

After implementation:
1. Run harness: `bazel run //harness:harness`
2. Verify bot logs appear with `[BOT]` prefix in real-time
3. Verify strategy logs show quote decisions every book update
4. Verify no 2-second gaps between quotes

## Risks

- **Log volume**: With throttle removed and verbose logging, expect high log volume. This is acceptable for observability - can tune later.
- **Performance**: Logging on every book update has minimal overhead with async logback appenders.

---

## Additional Finding: Critical Order Tracking Bugs

### Bug 1: `cancelOrder()` never publishes `Event.OrderCanceled`

**File:** `kraken-client/.../KrakenPrivateWs.kt` (lines 237-244)

**Current:**
```kotlin
private suspend fun cancelOrder(cmd: Command.CancelOrder) {
    val response = restClient.cancelOrder(cmd.orderId)
    val result = response["result"]?.jsonPrimitive?.contentOrNull
    if (result == "success") {
        logger.info { "Order canceled: ${cmd.orderId}" }
        // BUG: OrderManager never learns about this!
    }
}
```

**Fix:**
```kotlin
private suspend fun cancelOrder(cmd: Command.CancelOrder) {
    val response = restClient.cancelOrder(cmd.orderId)
    val result = response["result"]?.jsonPrimitive?.contentOrNull
    if (result == "success") {
        logger.info { "Order canceled: ${cmd.orderId}" }
        publishEvent(
            Event.OrderCanceled(
                orderId = cmd.orderId,
                clOrdId = "",  // We don't have this here, but OrderManager can look up by orderId
                reason = "user_requested",
                timestamp = System.currentTimeMillis(),
            )
        )
    } else {
        val error = response["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
        logger.error { "Cancel failed for ${cmd.orderId}: $error" }
    }
}
```

**Also need:** Update `OrderManager.onOrderCanceled()` to handle lookup by orderId when clOrdId is empty.

### Bug 2: `amendOrder()` doesn't track state changes

**Problem:** Kraken amend (edit) can:
1. Change the price on the existing order
2. Return a NEW orderId (replacing the old one)

**Current code doesn't handle either case!**

**File:** `kraken-client/.../KrakenPrivateWs.kt` (lines 257-272)

**Fix - Option A (simple):** Trust Kraken's `open_orders` feed to send updates. But we need to process order updates, not just acceptances.

**Fix - Option B (robust):** Publish `Event.OrderAmended` with old/new state.

**Recommended: Option A** - Refactor `processOpenOrders()` to:
1. Track known orders
2. Detect when order disappears (canceled) or price changes (amended)
3. Publish appropriate events

### Bug 3: `processOpenOrders()` only publishes `OrderAccepted`

**Current behavior:**
- Every order in the feed → `Event.OrderAccepted`
- No tracking of removed orders
- No tracking of amended orders

**This causes:**
- Duplicate `OrderAccepted` events for the same order
- No `OrderCanceled` when order disappears
- No update when order is amended

**Fix:** Maintain a local set of known orderIds and diff against incoming orders.

```kotlin
private val knownOrders = mutableMapOf<String, KnownOrder>()

private data class KnownOrder(
    val orderId: String,
    val price: BigDecimal,
    val qty: BigDecimal,
)

private fun processOpenOrders(obj: JsonObject) {
    val orders = obj["orders"]?.jsonArray ?: return
    val incomingOrderIds = mutableSetOf<String>()
    
    for (order in orders) {
        val o = order.jsonObject
        val orderId = o["order_id"]?.jsonPrimitive?.contentOrNull ?: continue
        incomingOrderIds.add(orderId)
        
        val limitPrice = o["limit_price"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val qty = o["qty"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        
        val existing = knownOrders[orderId]
        if (existing == null) {
            // New order - publish OrderAccepted
            knownOrders[orderId] = KnownOrder(orderId, BigDecimal.valueOf(limitPrice), BigDecimal.valueOf(qty))
            publishEvent(Event.OrderAccepted(...))
        } else if (existing.price != BigDecimal.valueOf(limitPrice)) {
            // Price changed - order was amended
            knownOrders[orderId] = existing.copy(price = BigDecimal.valueOf(limitPrice))
            logger.info { "Order amended: $orderId price=${existing.price} -> $limitPrice" }
            // Could publish Event.OrderAmended if we add that event type
        }
    }
    
    // Check for orders that disappeared (canceled or filled)
    val removedOrders = knownOrders.keys - incomingOrderIds
    for (orderId in removedOrders) {
        knownOrders.remove(orderId)
        publishEvent(
            Event.OrderCanceled(
                orderId = orderId,
                clOrdId = "",
                reason = "removed_from_open_orders",
                timestamp = System.currentTimeMillis(),
            )
        )
    }
}
```

**Note:** This needs careful handling of the initial `open_orders_snapshot` vs subsequent updates.

---

## Updated Implementation Order

1. **Harness log streaming** - Enable visibility first
2. **Remove requote throttle** - Enable responsive quoting
3. **Fix cancel event publishing** - Critical bug fix
4. **Fix open_orders processing** - Track removals and amendments
5. **Add baseline strategy logging** - Observability
6. **Update agent prompt** - Future strategies

## Updated Files Modified

| File | Change |
|------|--------|
| `harness/.../Harness.kt` | Stream bot logs + update prompt |
| `bot/.../BotConfig.kt` | Remove requote throttle |
| `kraken-client/.../KrakenPrivateWs.kt` | Fix cancel event + order tracking |
| `execution/.../OrderManager.kt` | Handle orderId-only cancels |
| `strategy/.../AgentXrpStrategy.kt` | Add decision logging |
| `bot/.../Pipeline.kt` | Reduce monitoring frequency |
