# Evolution Log - PF_XRPUSD

This file tracks the evolution of AgentXrpStrategy.kt across epochs.

## How This Works

Each epoch:
1. You modify the strategy in `strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt`
2. The strategy runs live until 50 trades complete (or 2 hour safety timeout)
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

---

## Epoch 1 - FAILED at 2026-01-09 20:10 UTC

### Failure: RUNTIME_CRASH

```
Error: Could not find or load main class com.didrikquant.bot.MainKt
Caused by: java.lang.ClassNotFoundException: com.didrikquant.bot.MainKt
```

### Attempted Changes

```diff
diff --git a/.claude/settings.json b/.claude/settings.json
index 07fa427..90c1f1c 100644
--- a/.claude/settings.json
+++ b/.claude/settings.json
@@ -1,5 +1,14 @@
 {
-  "enabledPlugins": {
-    "superpowers@superpowers-marketplace": true
+  "permissions": {
+    "allow": [
+      "Bash(bazel build:*)",
+      "Bash(bazel test:*)",
+      "Bash(dq:*)",
+      "Read",
+      "Write(strategy/**)",
+      "Write(agents/**)",
+      "Edit(strategy/**)",
+      "Edit(agents/**)"
+    ]
   }
-}
+}
\ No newline at end of file
diff --git a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
index d8da1f3..c19016f 100644
--- a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
+++ b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
@@ -26,7 +26,11 @@ public class AgentXrpStrategy(
     private val imbalanceThreshold: BigDecimal = BigDecimal("0.3"),
     private val imbalanceSpreadWidenBps: Int = 3,
     private val imbalanceSizeReduction: BigDecimal = BigDecimal("0.5"),
+    private val momentumWindowSize: Int = 10,
+    private val momentumThresholdBps: Int = 8,
+    private val momentumSpreadWidenBps: Int = 6,
 ) : Strategy {
+    private val recentMids = ArrayDeque<BigDecimal>(momentumWindowSize + 1)
     override fun onBookSnapshot(
         book: OrderBookSnapshot,
         position: BigDecimal,
@@ -36,16 +40,25 @@ public class AgentXrpStrategy(
 
         val mid = book.midPrice ?: return emptyList()
 
+        // Track recent mids for momentum calculation
+        recentMids.addLast(mid)
+        if (recentMids.size > momentumWindowSize) {
+            recentMids.removeFirst()
+        }
+
         val marketSpreadBps = book.spreadBps?.toInt() ?: minSpreadBps
         val baseSpreadBps = maxOf(marketSpreadBps, minSpreadBps)
         val depthAdjustment = calculateDepthAdjustment(book, mid)
-        val baseAdaptiveSpreadBps = baseSpreadBps + spreadBufferBps + depthAdjustment
+        val momentumAdjustment = calculateMomentumAdjustment(mid)
+        val baseAdaptiveSpreadBps = baseSpreadBps + spreadBufferBps + depthAdjustment + momentumAdjustment.first
 
         val imbalance = calculateOrderBookImbalance(book)
         val (bidSpreadAdjustBps, askSpreadAdjustBps) = calculateImbalanceSpreadAdjustment(imbalance)
 
-        val bidSpreadBps = baseAdaptiveSpreadBps + bidSpreadAdjustBps
-        val askSpreadBps = baseAdaptiveSpreadBps + askSpreadAdjustBps
+        // Directional momentum: widen the side that would get adversely selected
+        val (momentumBidAdjust, momentumAskAdjust) = momentumAdjustment.second
+        val bidSpreadBps = baseAdaptiveSpreadBps + bidSpreadAdjustBps + momentumBidAdjust
+        val askSpreadBps = baseAdaptiveSpreadBps + askSpreadAdjustBps + momentumAskAdjust
 
         val bidSpreadDecimal = BigDecimal(bidSpreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
         val askSpreadDecimal = BigDecimal(askSpreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
@@ -83,8 +96,13 @@ public class AgentXrpStrategy(
         actions.addAll(manageOrder(existingAsk, Side.SELL, askPrice, askSize, mid))
 
         if (actions.isNotEmpty() || openOrders.isEmpty()) {
+            val momAdj = if (momentumBidAdjust > 0 || momentumAskAdjust > 0) {
+                " mom=+${momentumBidAdjust}b/+${momentumAskAdjust}a"
+            } else {
+                ""
+            }
             logger.info {
-                "mid=$mid spread=${book.spreadBps}bps pos=$position | " +
+                "mid=$mid spread=${book.spreadBps}bps pos=$position$momAdj | " +
                     "bid=$bidPrice x $bidSize, ask=$askPrice x $askSize | " +
                     "orders=${openOrders.size} actions=${actions.size}"
             }
@@ -210,4 +228,48 @@ public class AgentXrpStrategy(
             Pair(reducedMultiplier, BigDecimal.ONE)
         }
     }
+
+    /**
+     * Calculate momentum-based spread adjustment.
+     * Returns: Pair(symmetricWiden, Pair(bidDirectionalWiden, askDirectionalWiden))
+     *
+     * When price is moving up quickly, we widen bid side (avoid buying into uptrend then reversal).
+     * When price is moving down quickly, we widen ask side (avoid selling into downtrend then reversal).
+     */
+    private fun calculateMomentumAdjustment(currentMid: BigDecimal): Pair<Int, Pair<Int, Int>> {
+        if (recentMids.size < 3) {
+            return Pair(0, Pair(0, 0))
+        }
+
+        val oldestMid = recentMids.first()
+        if (oldestMid <= BigDecimal.ZERO) {
+            return Pair(0, Pair(0, 0))
+        }
+
+        val priceDiff = currentMid - oldestMid
+        val momentumBps = priceDiff
+            .divide(oldestMid, 8, RoundingMode.HALF_UP)
+            .multiply(BigDecimal("10000"))
+            .toInt()
+
+        val absMomentumBps = kotlin.math.abs(momentumBps)
+
+        if (absMomentumBps < momentumThresholdBps) {
+            return Pair(0, Pair(0, 0))
+        }
+
+        // Scale the adjustment based on how far above threshold we are
+        val excessMomentum = absMomentumBps - momentumThresholdBps
+        val directionalAdjust = minOf(excessMomentum, momentumSpreadWidenBps)
+
+        return if (momentumBps > 0) {
+            // Price moving up - widen bid to avoid adverse selection on buys
+            logger.debug { "Momentum UP ${momentumBps}bps, widening bid by $directionalAdjust" }
+            Pair(0, Pair(directionalAdjust, 0))
+        } else {
+            // Price moving down - widen ask to avoid adverse selection on sells
+            logger.debug { "Momentum DOWN ${momentumBps}bps, widening ask by $directionalAdjust" }
+            Pair(0, Pair(0, directionalAdjust))
+        }
+    }
 }
```
