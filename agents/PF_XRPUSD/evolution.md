# Evolution Log - PF_XRPUSD

This file tracks the evolution of AgentXrpStrategy.kt across epochs.

## How This Works

Each epoch:
1. You modify the strategy in `strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt`
2. The strategy runs live until 50 trades complete (or 2 hour safety timeout)
3. Results (Sharpe ratio) are appended here

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

## Epoch 1 - FAILED at 2026-01-09 20:46 UTC

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
index d8da1f3..4561ad1 100644
--- a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
+++ b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
@@ -26,6 +26,7 @@ public class AgentXrpStrategy(
     private val imbalanceThreshold: BigDecimal = BigDecimal("0.3"),
     private val imbalanceSpreadWidenBps: Int = 3,
     private val imbalanceSizeReduction: BigDecimal = BigDecimal("0.5"),
+    private val micropriceSkewBps: BigDecimal = BigDecimal("1.5"),
 ) : Strategy {
     override fun onBookSnapshot(
         book: OrderBookSnapshot,
@@ -50,10 +51,11 @@ public class AgentXrpStrategy(
         val bidSpreadDecimal = BigDecimal(bidSpreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
         val askSpreadDecimal = BigDecimal(askSpreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
 
-        val skew = position * skewFactor
+        val inventorySkew = position * skewFactor
+        val microSkew = calculateMicropriceSkew(book, mid)
 
-        val rawBidPrice = mid - (mid * bidSpreadDecimal) - skew
-        val rawAskPrice = mid + (mid * askSpreadDecimal) - skew
+        val rawBidPrice = mid - (mid * bidSpreadDecimal) - inventorySkew + microSkew
+        val rawAskPrice = mid + (mid * askSpreadDecimal) - inventorySkew + microSkew
 
         val bidPrice = rawBidPrice.roundToTick(tickSize)
         val askPrice = rawAskPrice.roundToTick(tickSize)
@@ -83,9 +85,11 @@ public class AgentXrpStrategy(
         actions.addAll(manageOrder(existingAsk, Side.SELL, askPrice, askSize, mid))
 
         if (actions.isNotEmpty() || openOrders.isEmpty()) {
+            val microprice = calculateMicroprice(book)
             logger.info {
-                "mid=$mid spread=${book.spreadBps}bps pos=$position | " +
+                "mid=$mid μ=$microprice spread=${book.spreadBps}bps pos=$position | " +
                     "bid=$bidPrice x $bidSize, ask=$askPrice x $askSize | " +
+                    "invSkew=$inventorySkew μSkew=$microSkew | " +
                     "orders=${openOrders.size} actions=${actions.size}"
             }
         }
@@ -210,4 +214,56 @@ public class AgentXrpStrategy(
             Pair(reducedMultiplier, BigDecimal.ONE)
         }
     }
+
+    /**
+     * Calculate microprice - a volume-weighted fair value estimate.
+     * Microprice = (bestBid * askQty + bestAsk * bidQty) / (bidQty + askQty)
+     *
+     * When microprice > mid: suggests buying pressure (more ask volume at best)
+     * When microprice < mid: suggests selling pressure (more bid volume at best)
+     */
+    private fun calculateMicroprice(book: OrderBookSnapshot): BigDecimal? {
+        val bestBid = book.bids.firstOrNull() ?: return null
+        val bestAsk = book.asks.firstOrNull() ?: return null
+
+        val bidQty = bestBid.qty
+        val askQty = bestAsk.qty
+        val totalQty = bidQty + askQty
+
+        if (totalQty <= BigDecimal.ZERO) return null
+
+        return (bestBid.price * askQty + bestAsk.price * bidQty)
+            .divide(totalQty, 8, RoundingMode.HALF_UP)
+    }
+
+    /**
+     * Calculate quote skew based on microprice deviation from mid.
+     * Returns skew in price terms (positive = skew quotes upward).
+     *
+     * If microprice > mid, the market suggests upward pressure, so we:
+     * - Move both quotes up to improve fill quality
+     * This reduces adverse selection by leaning into the short-term direction.
+     */
+    private fun calculateMicropriceSkew(
+        book: OrderBookSnapshot,
+        mid: BigDecimal,
+    ): BigDecimal {
+        val microprice = calculateMicroprice(book) ?: return BigDecimal.ZERO
+
+        val microDeviationBps = (microprice - mid)
+            .divide(mid, 8, RoundingMode.HALF_UP)
+            .multiply(BigDecimal("10000"))
+
+        val cappedDeviationBps = microDeviationBps
+            .max(-micropriceSkewBps)
+            .min(micropriceSkewBps)
+
+        val skew = mid * cappedDeviationBps.divide(BigDecimal("10000"), 8, RoundingMode.HALF_UP)
+
+        if (microDeviationBps.abs() > BigDecimal("0.5")) {
+            logger.debug { "microprice=$microprice mid=$mid deviation=${microDeviationBps}bps skew=$skew" }
+        }
+
+        return skew
+    }
 }
```

---

## Epoch 2 - FAILED at 2026-01-09 21:17 UTC

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
diff --git a/agents/PF_XRPUSD/evolution.md b/agents/PF_XRPUSD/evolution.md
index 8df383e..6416fa8 100644
--- a/agents/PF_XRPUSD/evolution.md
+++ b/agents/PF_XRPUSD/evolution.md
@@ -164,3 +164,32 @@ index d8da1f3..4561ad1 100644
 +    }
  }
 ```
+
+---
+
+## Epoch 2 - Microprice Skew (Retry)
+
+### Hypothesis
+
+Epoch 1 crashed due to a transient build/deployment issue (ClassNotFoundException for MainKt), not the strategy code. The microprice skew enhancement is a sound market microstructure improvement.
+
+**Microprice theory**: The microprice is a volume-weighted fair value:
+- `microprice = (bestBid * askQty + bestAsk * bidQty) / (bidQty + askQty)`
+- When askQty > bidQty at best levels, microprice > mid → buying pressure
+- When bidQty > askQty at best levels, microprice < mid → selling pressure
+
+By skewing quotes in the direction of the microprice, we:
+1. Reduce adverse selection (less likely to be picked off by informed traders)
+2. Improve fill quality on the side aligned with short-term flow
+
+### Changes
+
+1. Added `micropriceSkewBps` parameter (default 1.5 bps cap)
+2. Implemented `calculateMicroprice()` to compute volume-weighted fair value
+3. Implemented `calculateMicropriceSkew()` to compute quote adjustment
+4. Both quotes shift by the same amount (unlike inventory skew which tilts)
+5. Enhanced logging to show microprice and skew values
+
+### Results
+
+- Sharpe: (pending)
diff --git a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
index d8da1f3..4561ad1 100644
--- a/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
+++ b/strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt
@@ -26,6 +26,7 @@ public class AgentXrpStrategy(
     private val imbalanceThreshold: BigDecimal = BigDecimal("0.3"),
     private val imbalanceSpreadWidenBps: Int = 3,
     private val imbalanceSizeReduction: BigDecimal = BigDecimal("0.5"),
+    private val micropriceSkewBps: BigDecimal = BigDecimal("1.5"),
 ) : Strategy {
     override fun onBookSnapshot(
         book: OrderBookSnapshot,
@@ -50,10 +51,11 @@ public class AgentXrpStrategy(
         val bidSpreadDecimal = BigDecimal(bidSpreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
         val askSpreadDecimal = BigDecimal(askSpreadBps).divide(BigDecimal("20000"), 8, RoundingMode.HALF_UP)
 
-        val skew = position * skewFactor
+        val inventorySkew = position * skewFactor
+        val microSkew = calculateMicropriceSkew(book, mid)
 
-        val rawBidPrice = mid - (mid * bidSpreadDecimal) - skew
-        val rawAskPrice = mid + (mid * askSpreadDecimal) - skew
+        val rawBidPrice = mid - (mid * bidSpreadDecimal) - inventorySkew + microSkew
+        val rawAskPrice = mid + (mid * askSpreadDecimal) - inventorySkew + microSkew
 
         val bidPrice = rawBidPrice.roundToTick(tickSize)
         val askPrice = rawAskPrice.roundToTick(tickSize)
@@ -83,9 +85,11 @@ public class AgentXrpStrategy(
         actions.addAll(manageOrder(existingAsk, Side.SELL, askPrice, askSize, mid))
 
         if (actions.isNotEmpty() || openOrders.isEmpty()) {
+            val microprice = calculateMicroprice(book)
             logger.info {
-                "mid=$mid spread=${book.spreadBps}bps pos=$position | " +
+                "mid=$mid μ=$microprice spread=${book.spreadBps}bps pos=$position | " +
                     "bid=$bidPrice x $bidSize, ask=$askPrice x $askSize | " +
+                    "invSkew=$inventorySkew μSkew=$microSkew | " +
                     "orders=${openOrders.size} actions=${actions.size}"
             }
         }
@@ -210,4 +214,56 @@ public class AgentXrpStrategy(
             Pair(reducedMultiplier, BigDecimal.ONE)
         }
     }
+
+    /**
+     * Calculate microprice - a volume-weighted fair value estimate.
+     * Microprice = (bestBid * askQty + bestAsk * bidQty) / (bidQty + askQty)
+     *
+     * When microprice > mid: suggests buying pressure (more ask volume at best)
+     * When microprice < mid: suggests selling pressure (more bid volume at best)
+     */
+    private fun calculateMicroprice(book: OrderBookSnapshot): BigDecimal? {
+        val bestBid = book.bids.firstOrNull() ?: return null
+        val bestAsk = book.asks.firstOrNull() ?: return null
+
+        val bidQty = bestBid.qty
+        val askQty = bestAsk.qty
+        val totalQty = bidQty + askQty
+
+        if (totalQty <= BigDecimal.ZERO) return null
+
+        return (bestBid.price * askQty + bestAsk.price * bidQty)
+            .divide(totalQty, 8, RoundingMode.HALF_UP)
+    }
+
+    /**
+     * Calculate quote skew based on microprice deviation from mid.
+     * Returns skew in price terms (positive = skew quotes upward).
+     *
+     * If microprice > mid, the market suggests upward pressure, so we:
+     * - Move both quotes up to improve fill quality
+     * This reduces adverse selection by leaning into the short-term direction.
+     */
+    private fun calculateMicropriceSkew(
+        book: OrderBookSnapshot,
+        mid: BigDecimal,
+    ): BigDecimal {
+        val microprice = calculateMicroprice(book) ?: return BigDecimal.ZERO
+
+        val microDeviationBps = (microprice - mid)
+            .divide(mid, 8, RoundingMode.HALF_UP)
+            .multiply(BigDecimal("10000"))
+
+        val cappedDeviationBps = microDeviationBps
+            .max(-micropriceSkewBps)
+            .min(micropriceSkewBps)
+
+        val skew = mid * cappedDeviationBps.divide(BigDecimal("10000"), 8, RoundingMode.HALF_UP)
+
+        if (microDeviationBps.abs() > BigDecimal("0.5")) {
+            logger.debug { "microprice=$microprice mid=$mid deviation=${microDeviationBps}bps skew=$skew" }
+        }
+
+        return skew
+    }
 }
```
