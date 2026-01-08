# Evolution Log - PF_XRPUSD

This file tracks the evolution of AgentXrpStrategy.kt across epochs.

## How This Works

Each epoch:
1. You modify the strategy in `strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt`
2. The strategy runs live for 1 hour
3. Results (Sharpe ratio) are appended here

## Tools Available

- `dq fills` - View fills from last epoch
- `dq book --at <timestamp>` - View order book at a specific timestamp (epoch millis)

## Strategy File

`strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt`

## Epoch 0 - Baseline

### Changes

Initial strategy: copy of SimpleMarketMaker with default parameters:
- spreadBps: 10
- orderSize: 15
- skewFactor: 0.0001
- tickSize: 0.00001

### Results

- Sharpe: (pending first run)

---

## Epoch 1 - FAILED at 2026-01-08 00:50 UTC

### Failure: RUNTIME_CRASH

```
kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled
01:50:25.923 [DefaultDispatcher-worker-5] INFO  c.didrikquant.kraken.KrakenPublicWs - Reconnecting Futures WS in 5 seconds...
01:50:25.924 [DefaultDispatcher-worker-2] ERROR c.didrikquant.kraken.KrakenPrivateWs - Private Futures WS error
kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled
01:50:25.925 [DefaultDispatcher-worker-2] INFO  c.didrikquant.kraken.KrakenPrivateWs - Reconnecting private Futures WS in 5 seconds...
01:50:25.939 [Thread-17] INFO  c.d.replay.recorder.EventRecorder - Event recorder closed
01:50:25.939 [Thread-17] INFO  com.didrikquant.bot.Pipeline - Disruptor pipeline stopped
01:50:25.939 [Thread-17] INFO  com.didrikquant.bot.Main - Shutdown complete
```

### Attempted Changes

```diff
diff --git a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
index f57fc6c..3cb4c3d 100644
--- a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
+++ b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
@@ -11,6 +11,7 @@ public class AgentXrpStrategy(
     private val orderSize: BigDecimal = BigDecimal("15"),
     private val skewFactor: BigDecimal = BigDecimal("0.0001"),
     private val tickSize: BigDecimal = BigDecimal("0.00001"),
+    private val maxPosition: BigDecimal = BigDecimal("100"),
 ) : Strategy {
 
     override fun onOrderBook(book: OrderBook, position: BigDecimal): List<OrderIntent> {
@@ -32,9 +33,31 @@ public class AgentXrpStrategy(
         val bidPrice = rawBidPrice.roundToTick(tickSize)
         val askPrice = rawAskPrice.roundToTick(tickSize)
 
-        return listOf(
-            OrderIntent(Side.BUY, bidPrice, orderSize),
-            OrderIntent(Side.SELL, askPrice, orderSize),
-        )
+        val inventoryUtilization = position.abs().divide(maxPosition, 8, RoundingMode.HALF_UP)
+        val availableCapacity = BigDecimal.ONE - inventoryUtilization.min(BigDecimal.ONE)
+
+        val bidSize = if (position >= BigDecimal.ZERO) {
+            orderSize * availableCapacity
+        } else {
+            orderSize
+        }
+
+        val askSize = if (position <= BigDecimal.ZERO) {
+            orderSize * availableCapacity
+        } else {
+            orderSize
+        }
+
+        val orders = mutableListOf<OrderIntent>()
+
+        val minOrderSize = BigDecimal.ONE
+        if (bidSize >= minOrderSize) {
+            orders.add(OrderIntent(Side.BUY, bidPrice, bidSize.setScale(0, RoundingMode.DOWN)))
+        }
+        if (askSize >= minOrderSize) {
+            orders.add(OrderIntent(Side.SELL, askPrice, askSize.setScale(0, RoundingMode.DOWN)))
+        }
+
+        return orders
     }
 }
```

---

## Epoch 2 - 2026-01-08 00:56 to 2026-01-08 00:57 UTC

### Changes

```diff
diff --git a/agents/PF_XRPUSD/evolution.md b/agents/PF_XRPUSD/evolution.md
index bdd2324..f4fcc48 100644
--- a/agents/PF_XRPUSD/evolution.md
+++ b/agents/PF_XRPUSD/evolution.md
@@ -31,3 +31,51 @@ Initial strategy: copy of SimpleMarketMaker with default parameters:
 ### Results
 
 - Sharpe: (pending first run)
+
+## Epoch 1 - Inventory-Aware Sizing (CRASHED)
+
+### Changes
+
+Added complex inventory-aware sizing that scaled order sizes based on position utilization:
+- Added `maxPosition = 100`
+- Calculated `positionRatio` and scaling factors
+- Reduced order sizes dynamically
+
+### Problem
+
+The scaling logic could produce order sizes below `minOrderSize = 1`:
+- At high position utilization, `bidScale` or `askScale` approached 0
+- `orderSize * 0.025 = 0.375` rounds to 0
+- Orders with size < 1 filtered out, but this left the market unquoted
+
+The "crash" was actually a normal shutdown (coroutine cancellation), not a strategy-induced failure.
+
+### Results
+
+- Sharpe: N/A (system shutdown during epoch)
+
+## Epoch 2 - Graduated Inventory Management
+
+### Changes
+
+Improved inventory management with graduated sizing (builds on initial Epoch 2 changes):
+- `orderSize: 15 -> 10` (reduced to stay within maxOrderSize=10 limit)
+- `skewFactor: 0.0001 -> 0.0002` (increased skew for faster inventory mean-reversion)
+- `maxPosition: 80` (conservative buffer below risk limit of 100)
+- **Graduated sizing**: Scale order size linearly with position
+  - At position=0: full size on both sides (10)
+  - As position approaches +80: bid size scales down (min 10% = 1)
+  - As position approaches -80: ask size scales down (min 10% = 1)
+- **Always quotes both sides** with minimum size 1 to maintain market presence
+- Fixes original Epoch 2 approach that completely stopped quoting at limits
+
+### Rationale
+
+1. Safety: Always returns exactly 2 orders (no empty list edge cases)
+2. Graduated: Smooth scaling reduces risk as position grows, without abrupt cutoffs
+3. Market presence: Minimum size 1 ensures we're always quoted on both sides
+4. Stronger skew: 2x skew factor + graduated sizing provides dual mean-reversion pressure
+
+### Results
+
+- Sharpe: (pending)
diff --git a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
index f57fc6c..8816d1e 100644
--- a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
+++ b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
@@ -8,9 +8,10 @@ import java.math.RoundingMode
 
 public class AgentXrpStrategy(
     private val spreadBps: Int = 10,
-    private val orderSize: BigDecimal = BigDecimal("15"),
-    private val skewFactor: BigDecimal = BigDecimal("0.0001"),
+    private val orderSize: BigDecimal = BigDecimal("10"),
+    private val skewFactor: BigDecimal = BigDecimal("0.0002"),
     private val tickSize: BigDecimal = BigDecimal("0.00001"),
+    private val maxPosition: BigDecimal = BigDecimal("80"),
 ) : Strategy {
 
     override fun onOrderBook(book: OrderBook, position: BigDecimal): List<OrderIntent> {
@@ -32,9 +33,21 @@ public class AgentXrpStrategy(
         val bidPrice = rawBidPrice.roundToTick(tickSize)
         val askPrice = rawAskPrice.roundToTick(tickSize)
 
+        // Inventory-aware sizing: scale down size as we approach position limits
+        // Always quote both sides with at least size 1 to maintain market presence
+        val positionRatio = position.divide(maxPosition, 8, RoundingMode.HALF_UP)
+            .max(-BigDecimal.ONE).min(BigDecimal.ONE)
+
+        // When long (positive ratio), reduce bid size; when short (negative ratio), reduce ask size
+        val bidMultiplier = (BigDecimal.ONE - positionRatio.max(BigDecimal.ZERO)).max(BigDecimal("0.1"))
+        val askMultiplier = (BigDecimal.ONE + positionRatio.min(BigDecimal.ZERO)).max(BigDecimal("0.1"))
+
+        val bidSize = (orderSize * bidMultiplier).setScale(0, RoundingMode.DOWN).max(BigDecimal.ONE)
+        val askSize = (orderSize * askMultiplier).setScale(0, RoundingMode.DOWN).max(BigDecimal.ONE)
+
         return listOf(
-            OrderIntent(Side.BUY, bidPrice, orderSize),
-            OrderIntent(Side.SELL, askPrice, orderSize),
+            OrderIntent(Side.BUY, bidPrice, bidSize),
+            OrderIntent(Side.SELL, askPrice, askSize),
         )
     }
 }
```

### Results

- Sharpe: 0
