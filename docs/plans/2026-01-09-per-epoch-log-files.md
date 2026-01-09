# Per-Epoch Log Files Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Write bot output to per-epoch log files so an LLM can debug runs on demand.

**Architecture:** Modify Harness to tee bot stdout to `agents/{instrument}/logs/epoch-{N}.log` while also logging to console. No bot changes needed.

**Tech Stack:** Kotlin, java.nio.file, existing Harness infrastructure

---

### Task 1: Add epoch parameter to runBot and create log file

**Files:**
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:63` (call site)
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:243` (function signature)

**Step 1: Update runBot signature to accept epoch**

Change line 243 from:
```kotlin
private fun runBot(worktreePath: Path, bazelBinPath: String): BotResult {
```
to:
```kotlin
private fun runBot(worktreePath: Path, bazelBinPath: String, epoch: Int): BotResult {
```

**Step 2: Update call site to pass epoch**

Change line 63 from:
```kotlin
val botResult = runBot(worktreePath, buildResult.bazelBinPath)
```
to:
```kotlin
val botResult = runBot(worktreePath, buildResult.bazelBinPath, epoch)
```

**Step 3: Build to verify syntax**

Run: `bazel build //harness`
Expected: SUCCESS

**Step 4: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/Harness.kt
git commit -m "refactor(harness): pass epoch to runBot"
```

---

### Task 2: Create log directory and file writer in runBot

**Files:**
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:243-250`

**Step 1: Add log file setup after the logger.info line**

After line 244 (`logger.info { "Running bot for ${config.epochTradeCount} trades..." }`), add:

```kotlin
// Create per-epoch log file
val logDir = config.agentDir.resolve("logs")
Files.createDirectories(logDir)
val logFile = logDir.resolve("epoch-$epoch.log")
val logWriter = Files.newBufferedWriter(logFile)
logger.info { "Writing bot log to $logFile" }
```

**Step 2: Build to verify syntax**

Run: `bazel build //harness`
Expected: SUCCESS

**Step 3: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/Harness.kt
git commit -m "feat(harness): create per-epoch log file"
```

---

### Task 3: Write bot output to log file

**Files:**
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:276-285` (output thread)

**Step 1: Modify the output thread to write to log file**

Change the output thread block from:
```kotlin
val outputThread = Thread {
    process.inputStream.bufferedReader().forEachLine { line ->
        logger.info { "[BOT] $line" }
        outputCapture.appendLine(line)
    }
}.apply {
    isDaemon = true
    name = "bot-output-reader"
    start()
}
```

to:
```kotlin
val outputThread = Thread {
    process.inputStream.bufferedReader().forEachLine { line ->
        logger.info { "[BOT] $line" }
        outputCapture.appendLine(line)
        logWriter.write(line)
        logWriter.newLine()
        logWriter.flush()
    }
}.apply {
    isDaemon = true
    name = "bot-output-reader"
    start()
}
```

**Step 2: Build to verify syntax**

Run: `bazel build //harness`
Expected: SUCCESS

**Step 3: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/Harness.kt
git commit -m "feat(harness): write bot output to per-epoch log file"
```

---

### Task 4: Close log writer after bot exits

**Files:**
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:296` (after outputThread.join)

**Step 1: Close the log writer after the output thread joins**

After line 296 (`outputThread.join(2000)`), add:

```kotlin
logWriter.close()
logger.info { "Bot log written to $logFile" }
```

**Step 2: Build to verify syntax**

Run: `bazel build //harness`
Expected: SUCCESS

**Step 3: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/Harness.kt
git commit -m "feat(harness): close log writer after bot exits"
```

---

### Task 5: Add logFile to BotResult for potential future use

**Files:**
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:199` (BotResult data class)
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt:301-306` (return statements)

**Step 1: Add logFile field to BotResult**

Change line 199 from:
```kotlin
private data class BotResult(val crashed: Boolean, val exitCode: Int, val error: String? = null)
```
to:
```kotlin
private data class BotResult(val crashed: Boolean, val exitCode: Int, val error: String? = null, val logFile: Path? = null)
```

**Step 2: Update return statements to include logFile**

Change:
```kotlin
return if (exitCode != 0) {
    val error = extractCrashError(outputCapture.toString())
    BotResult(crashed = true, exitCode = exitCode, error = error)
} else {
    BotResult(crashed = false, exitCode = exitCode)
}
```
to:
```kotlin
return if (exitCode != 0) {
    val error = extractCrashError(outputCapture.toString())
    BotResult(crashed = true, exitCode = exitCode, error = error, logFile = logFile)
} else {
    BotResult(crashed = false, exitCode = exitCode, logFile = logFile)
}
```

**Step 3: Build to verify syntax**

Run: `bazel build //harness`
Expected: SUCCESS

**Step 4: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/Harness.kt
git commit -m "feat(harness): include logFile path in BotResult"
```

---

### Task 6: Final build and lint check

**Step 1: Build everything**

Run: `bazel build //...`
Expected: SUCCESS

**Step 2: Run lint**

Run: `bazel test //... --test_tag_filters=ktlint`
Expected: SUCCESS (or fix any issues)

**Step 3: Squash commits (optional)**

If you want a single commit:
```bash
git rebase -i HEAD~5
# squash all into one
git commit --amend -m "feat(harness): write bot output to per-epoch log files

Writes bot stdout to agents/{instrument}/logs/epoch-{N}.log for LLM debugging."
```

---

## Verification

After implementation, run the harness briefly and verify:

1. Log directory created: `ls agents/PF_XRPUSD/logs/`
2. Log file exists: `cat agents/PF_XRPUSD/logs/epoch-1.log`
3. Log contains bot output with timestamps
