# Claude Code Harness Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace opencode HTTP client with Claude Code CLI invocation in the harness.

**Architecture:** The harness spawns Claude Code CLI directly in each worktree instead of calling an HTTP server. Before invocation, it writes a locked-down `.claude/settings.json` to control agent permissions.

**Tech Stack:** Kotlin, ProcessBuilder, Claude Code CLI

---

### Task 1: Create ClaudeCodeClient

**Files:**
- Create: `harness/src/main/kotlin/com/didrikquant/harness/ClaudeCodeClient.kt`

**Step 1: Create the ClaudeCodeClient class**

```kotlin
package com.didrikquant.harness

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

public class ClaudeCodeClient {

    public fun runPrompt(workingDirectory: Path, prompt: String) {
        writeSettings(workingDirectory)

        val process = ProcessBuilder(
            "claude",
            "--model", "claude-opus-4-5-20250514",
            "-p", prompt
        )
            .directory(workingDirectory.toFile())
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("Claude Code exited with code $exitCode")
        }
    }

    private fun writeSettings(worktreePath: Path) {
        val settings = """
            {
              "permissions": {
                "allow": [
                  "Bash(bazel build:*)",
                  "Bash(bazel test:*)",
                  "Bash(dq:*)",
                  "Read",
                  "Write(strategy/**)",
                  "Write(agents/**)",
                  "Edit(strategy/**)",
                  "Edit(agents/**)"
                ]
              }
            }
        """.trimIndent()

        val claudeDir = worktreePath.resolve(".claude")
        Files.createDirectories(claudeDir)
        Files.writeString(claudeDir.resolve("settings.json"), settings)
        logger.info { "Wrote Claude Code settings to $claudeDir" }
    }
}
```

**Step 2: Verify it compiles**

Run: `bazel build //harness`
Expected: Build successful

**Step 3: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/ClaudeCodeClient.kt
git commit -m "feat(harness): add ClaudeCodeClient for CLI invocation"
```

---

### Task 2: Update Harness to use ClaudeCodeClient

**Files:**
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/Harness.kt`

**Step 1: Replace opencodeClient field with claudeClient**

Change line 16 from:
```kotlin
    private val opencodeClient = OpencodeClient(config.opencodeHost, config.opencodePort, config.opencodeModel)
```

To:
```kotlin
    private val claudeClient = ClaudeCodeClient()
```

**Step 2: Remove health check and opencode logging from run()**

Change lines 18-37 from:
```kotlin
    public fun run() {
        logger.info { "Starting harness for ${config.instrument}" }
        logger.info { "Strategy class: ${config.strategyClass}" }
        logger.info { "Epoch trades: ${config.epochTradeCount}" }
        logger.info { "Epoch max duration: ${config.epochMaxDurationMs}ms (safety timeout)" }
        logger.info { "Repo root: ${config.repoRoot}" }
        logger.info { "Opencode server: ${config.opencodeHost}:${config.opencodePort}" }
        logger.info { "Opencode model: ${config.opencodeModel}" }

        Files.createDirectories(config.agentDir)

        if (!opencodeClient.healthCheck()) {
            error(
                "Cannot connect to opencode server at ${config.opencodeHost}:${config.opencodePort}. Start it with: opencode serve --port ${config.opencodePort}"
            )
        }

        while (true) {
            runEpoch()
        }
    }
```

To:
```kotlin
    public fun run() {
        logger.info { "Starting harness for ${config.instrument}" }
        logger.info { "Strategy class: ${config.strategyClass}" }
        logger.info { "Epoch trades: ${config.epochTradeCount}" }
        logger.info { "Epoch max duration: ${config.epochMaxDurationMs}ms (safety timeout)" }
        logger.info { "Repo root: ${config.repoRoot}" }

        Files.createDirectories(config.agentDir)

        while (true) {
            runEpoch()
        }
    }
```

**Step 3: Simplify spawnAgent()**

Change lines 88-101 from:
```kotlin
    private fun spawnAgent(worktreePath: Path, epoch: Int) {
        val prompt = buildAgentPrompt(worktreePath, epoch)

        logger.info { "Sending prompt to opencode server" }

        val sessionId = opencodeClient.createSession()
        logger.info { "Created opencode session: $sessionId" }

        try {
            opencodeClient.sendPrompt(sessionId, prompt)
        } finally {
            opencodeClient.deleteSession(sessionId)
            logger.info { "Deleted opencode session: $sessionId" }
        }
    }
```

To:
```kotlin
    private fun spawnAgent(worktreePath: Path, epoch: Int) {
        val prompt = buildAgentPrompt(worktreePath, epoch)
        logger.info { "Spawning Claude Code agent in $worktreePath" }
        claudeClient.runPrompt(worktreePath, prompt)
        logger.info { "Agent completed" }
    }
```

**Step 4: Verify it compiles**

Run: `bazel build //harness`
Expected: Build successful

**Step 5: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/Harness.kt
git commit -m "feat(harness): use ClaudeCodeClient instead of OpencodeClient"
```

---

### Task 3: Remove opencode config from HarnessConfig

**Files:**
- Modify: `harness/src/main/kotlin/com/didrikquant/harness/HarnessConfig.kt`

**Step 1: Remove opencode fields from data class**

Change lines 7-18 from:
```kotlin
public data class HarnessConfig(
    val instrument: String,
    val epochTradeCount: Int = 50,
    val epochMaxDurationMs: Long = 7_200_000L, // 2 hours safety timeout
    val gracePeriodMs: Long = 60_000L,
    val strategyClass: String,
    val repoRoot: Path,
    val opencodeHost: String = "127.0.0.1",
    val opencodePort: Int = 4096,
    val opencodeModel: String = "anthropic/claude-opus-4-5",
    val krakenApiKey: String? = null,
    val krakenApiSecret: String? = null,
)
```

To:
```kotlin
public data class HarnessConfig(
    val instrument: String,
    val epochTradeCount: Int = 50,
    val epochMaxDurationMs: Long = 7_200_000L, // 2 hours safety timeout
    val gracePeriodMs: Long = 60_000L,
    val strategyClass: String,
    val repoRoot: Path,
    val krakenApiKey: String? = null,
    val krakenApiSecret: String? = null,
)
```

**Step 2: Remove opencode env var parsing from load()**

Change lines 36-38 from:
```kotlin
            val opencodeHost = get("OPENCODE_HOST") ?: "127.0.0.1"
            val opencodePort = get("OPENCODE_PORT")?.toIntOrNull() ?: 4096
            val opencodeModel = get("OPENCODE_MODEL") ?: "anthropic/claude-opus-4-5"
```

To: (delete these 3 lines entirely)

**Step 3: Remove opencode params from return statement**

Change lines 44-56 from:
```kotlin
            return HarnessConfig(
                instrument = instrument,
                epochTradeCount = epochTradeCount,
                epochMaxDurationMs = epochMaxDurationMs,
                gracePeriodMs = gracePeriodMs,
                strategyClass = strategyClass,
                repoRoot = repoRoot,
                opencodeHost = opencodeHost,
                opencodePort = opencodePort,
                opencodeModel = opencodeModel,
                krakenApiKey = krakenApiKey,
                krakenApiSecret = krakenApiSecret,
            )
```

To:
```kotlin
            return HarnessConfig(
                instrument = instrument,
                epochTradeCount = epochTradeCount,
                epochMaxDurationMs = epochMaxDurationMs,
                gracePeriodMs = gracePeriodMs,
                strategyClass = strategyClass,
                repoRoot = repoRoot,
                krakenApiKey = krakenApiKey,
                krakenApiSecret = krakenApiSecret,
            )
```

**Step 4: Verify it compiles**

Run: `bazel build //harness`
Expected: Build successful

**Step 5: Commit**

```bash
git add harness/src/main/kotlin/com/didrikquant/harness/HarnessConfig.kt
git commit -m "refactor(harness): remove opencode config fields"
```

---

### Task 4: Delete OpencodeClient

**Files:**
- Delete: `harness/src/main/kotlin/com/didrikquant/harness/OpencodeClient.kt`

**Step 1: Delete the file**

```bash
rm harness/src/main/kotlin/com/didrikquant/harness/OpencodeClient.kt
```

**Step 2: Verify build and tests pass**

Run: `bazel build //harness && bazel test //harness:ktlint`
Expected: Build and lint pass

**Step 3: Commit**

```bash
git add -A harness/src/main/kotlin/com/didrikquant/harness/OpencodeClient.kt
git commit -m "refactor(harness): delete OpencodeClient"
```

---

### Task 5: Run all tests and verify

**Step 1: Run full test suite**

Run: `bazel test //...`
Expected: All tests pass

**Step 2: Commit any fixes if needed**

---

### Task 6: Merge to main

**Step 1: Switch to main and merge**

```bash
cd /Users/diz/repos/dq
git checkout main
git merge feature/claude-code-harness --no-ff -m "feat(harness): switch from opencode to Claude Code CLI"
```

**Step 2: Clean up worktree**

```bash
git worktree remove .worktrees/claude-code-harness
git branch -d feature/claude-code-harness
```
