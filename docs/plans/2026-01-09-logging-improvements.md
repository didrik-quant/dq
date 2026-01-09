# Logging Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reduce log verbosity and improve signal-to-noise ratio for post-mortem debugging.

**Architecture:** Restructure log levels (INFO for actionable events, DEBUG for summaries, TRACE for raw data), add color coding to distinguish bot vs harness output, and eliminate duplicate prefixes by having harness pass through bot output directly.

**Tech Stack:** Logback with ANSI colors, Kotlin logging (mu.KotlinLogging)

---

### Task 1: Update Logback Configuration

**Files:**
- Modify: `bot/src/main/resources/logback.xml`

**Step 1: Replace logback.xml with colored, restructured config**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) %cyan(%-20.20logger{0}) - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Default: INFO for clean output -->
    <logger name="com.didrikquant" level="INFO"/>

    <!-- External libs: quiet unless problems -->
    <logger name="io.ktor" level="WARN"/>
    <logger name="io.netty" level="WARN"/>

    <root level="WARN">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

**Step 2: Verify config is valid XML**

Run: `xmllint --noout bot/src/main/resources/logback.xml`
Expected: No output (valid XML)

**Step 3: Commit**

```bash
git add bot/src/main/resources/logback.xml
git commit -m "feat(logging): add color coding and restructure log levels"
```

---

### Task 2: Update Harness to Pass Through Bot Output

**Files:**
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:3-10` (imports)
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:283-290` (output handling)

**Step 1: Add color constants and helper function after the logger declaration (line 10)**

After line 10 (`private val logger = KotlinLogging.logger {}`), add:

```kotlin
private const val CYAN = "\u001B[36m"
private const val YELLOW = "\u001B[33m"
private const val RED = "\u001B[31m"
private const val RESET = "\u001B[0m"

private fun harnessLog(msg: String, error: Boolean = false) {
    val timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
    val color = if (error) RED else CYAN
    println("$color$timestamp [HARNESS] $msg$RESET")
}
```

**Step 2: Replace bot output logging (line 285)**

Change line 285 from:
```kotlin
logger.info { "[BOT] $line" }
```

To:
```kotlin
println(line)
```

**Step 3: Replace harness logger.info calls with harnessLog**

Replace these logger calls with harnessLog:
- Line 20-28: Startup info logs
- Line 41: `"=== Starting Epoch $epoch ==="`
- Line 47: `"Skipping agent..."`
- Line 72: `"Bot failed: $failureType"` (use `error = true`)
- Line 91: `"Epoch $epoch Sharpe..."`
- Line 99: `"=== Completed Epoch $epoch ==="`
- Line 104: `"Spawning Claude Code agent..."`
- Line 106: `"Agent completed"`
- Line 202: `"Building bot in..."`
- Line 244: `"Running bot for..."`
- Line 251: `"Writing bot log to..."`
- Line 256-257: Bot launcher info
- Line 301: `"Bot did not exit..."` (use `error = true`)
- Line 309: `"Bot log written to..."`
- Line 312: `"Bot exited with code..."`
- Line 351: `"Committed evolution log..."`

Keep logger.error and logger.warn for actual errors (lines 56, 87, 213, 226-228).

**Step 4: Build and verify**

Run: `bazel build //harness`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/Harness.kt
git commit -m "feat(harness): color-coded output, direct bot passthrough"
```

---

### Task 3: Move Raw JSON to TRACE in KrakenPrivateWs

**Files:**
- Modify: `kraken-client/src/main/kotlin/com/didrikquant/kraken/KrakenPrivateWs.kt`

**Step 1: Change raw JSON logs from DEBUG to TRACE**

Change these lines from `logger.debug` to `logger.trace`:
- Line 108: `"Sending challenge request: $requestJson"`
- Line 118: `"Private WS received: $text"` (in handleInitialMessages)
- Line 150: `"Sending subscribe request: $requestJson"`
- Line 161: `"Private WS received: $text"` (in waitForSubscriptions)
- Line 191: `"Private WS received: $text"` (in handleMessages)

**Step 2: Change position updates from DEBUG to TRACE**

Change line 387 from:
```kotlin
logger.debug { "Position: $instrument balance=$balance pnl=$pnl" }
```

To:
```kotlin
logger.trace { "Position: $instrument balance=$balance pnl=$pnl" }
```

**Step 3: Build and verify**

Run: `bazel build //kraken-client`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add kraken-client/src/main/kotlin/com/didrikquant/kraken/KrakenPrivateWs.kt
git commit -m "refactor(kraken): move raw JSON and position ticks to TRACE"
```

---

### Task 4: Move Raw JSON to TRACE in KrakenPublicWs

**Files:**
- Modify: `kraken-client/src/main/kotlin/com/didrikquant/kraken/KrakenPublicWs.kt`

**Step 1: Change subscription confirmed log to summarize**

Change line 116 from:
```kotlin
logger.info { "Subscription confirmed: $text" }
```

To:
```kotlin
val feed = obj["feed"]?.jsonPrimitive?.contentOrNull
val productIds = obj["product_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
logger.info { "Subscription confirmed: $feed for $productIds" }
```

**Step 2: Build and verify**

Run: `bazel build //kraken-client`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add kraken-client/src/main/kotlin/com/didrikquant/kraken/KrakenPublicWs.kt
git commit -m "refactor(kraken): summarize subscription confirmation"
```

---

### Task 5: Summarize Book Snapshot in BookHandler

**Files:**
- Modify: `bot/src/main/kotlin/com/didrikquant/bot/handlers/BookHandler.kt:23`

**Step 1: Change book snapshot log to summarized format**

Change line 23 from:
```kotlin
logger.debug { "Book snapshot: ${event.orderBookSnapshot}" }
```

To:
```kotlin
val snap = event.orderBookSnapshot
logger.debug { "Book: bid=${snap?.bestBid} ask=${snap?.bestAsk} spread=${snap?.spreadBps}bps" }
```

**Step 2: Build and verify**

Run: `bazel build //bot`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add bot/src/main/kotlin/com/didrikquant/bot/handlers/BookHandler.kt
git commit -m "refactor(book): summarize book snapshot log"
```

---

### Task 6: Final Integration Test

**Step 1: Build everything**

Run: `bazel build //...`
Expected: BUILD SUCCESS

**Step 2: Run lint**

Run: `bazel test //... --test_tag_filters=ktlint`
Expected: All tests pass

**Step 3: Manual test (optional)**

Run harness briefly with `--dry-run` to verify:
- Bot output appears without `[BOT]` prefix and duplicate timestamps
- Harness output appears in cyan
- Log levels are cleaner (less DEBUG spam at INFO level)

**Step 4: Final commit if any fixups needed**

```bash
git add -A
git commit -m "fix(logging): address lint issues"
```

---

## Summary of Changes

| File | Change |
|------|--------|
| `bot/src/main/resources/logback.xml` | Colors, shorter logger names |
| `harness/.../Harness.kt` | Direct println for bot, cyan harnessLog() |
| `kraken-client/.../KrakenPrivateWs.kt` | Raw JSON + position ticks → TRACE |
| `kraken-client/.../KrakenPublicWs.kt` | Summarize subscription confirmation |
| `bot/.../handlers/BookHandler.kt` | Summarize book snapshot |

## Expected Result

```
09:31:39.557 INFO  AgentXrpStrategy     - mid=2.09058 spread=0.38bps pos=0 | bid=2.0898x21 ask=2.0912x28
09:31:39.571 INFO  TradingHandler       - Place: BUY 21 @ 2.0898
09:31:39.706 INFO  KrakenPrivateWs      - Order placed: a0cc5338 BUY 21 @ 2.0898
09:31:39.983 INFO  KrakenPrivateWs      - Fill: SELL 28 @ 2.0912
09:31:39.991 INFO  TradingHandler       - Fill: 28 @ 2.0912, pos=-28, pnl=0
```

Cyan harness lines:
```
09:31:32.386 [HARNESS] === Starting Epoch 1 ===
09:31:38.153 [HARNESS] Running bot for 50 trades (max 7200000ms)
```
