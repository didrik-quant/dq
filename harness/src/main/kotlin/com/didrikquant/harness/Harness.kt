package com.didrikquant.harness

import mu.KotlinLogging
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

public class Harness(private val config: HarnessConfig) {

    private val worktreeManager = WorktreeManager(config.repoRoot)
    private val evolutionLog = EvolutionLog(config.evolutionLogPath)
    private val claudeClient = ClaudeCodeClient()
    private val sharpeCalculator = SharpeCalculator(config.dataDir)

    public fun run() {
        logger.info { "Starting harness for ${config.instrument}" }
        logger.info { "Strategy class: ${config.strategyClass}" }
        logger.info { "Epoch trades: ${config.epochTradeCount}" }
        logger.info { "Epoch max duration: ${config.epochMaxDurationMs}ms (safety timeout)" }
        logger.info { "Repo root: ${config.repoRoot}" }
        logger.info { "Data dir: ${config.dataDir}" }
        logger.info { "Starting equity: ${config.startingEquity}" }
        logger.info { "Skip agent: ${config.skipAgent}" }
        logger.info { "Dry run: ${config.dryRun}" }

        Files.createDirectories(config.agentDir)

        while (true) {
            runEpoch()
        }
    }

    private fun runEpoch() {
        val epoch = evolutionLog.currentEpoch() + 1
        val branchName = "epoch-$epoch-${config.instrument}"

        logger.info { "=== Starting Epoch $epoch ===" }

        val worktreePath = worktreeManager.create(branchName)

        try {
            if (config.skipAgent) {
                logger.info { "Skipping agent (HARNESS_SKIP_AGENT=true)" }
            } else {
                spawnAgent(worktreePath, epoch)
            }

            val diff = worktreeManager.diff(worktreePath)

            val buildResult = build(worktreePath)
            if (!buildResult.success || buildResult.bazelBinPath == null) {
                logger.error { "Build failed, logging failure and continuing to next epoch" }
                evolutionLog.appendFailure(epoch, diff, "BUILD_FAILED", buildResult.error ?: "Unknown error")
                commitEvolutionLog("Epoch $epoch: BUILD_FAILED")
                return
            }

            val startTime = Instant.now()
            val botResult = runBot(worktreePath, buildResult.bazelBinPath, epoch)
            val endTime = Instant.now()

            if (botResult.crashed) {
                val failureType = if (botResult.error?.contains("RISK BREACH") == true) {
                    "RISK_BREACH"
                } else {
                    "RUNTIME_CRASH"
                }
                logger.error { "Bot failed: $failureType" }
                evolutionLog.appendFailure(epoch, diff, failureType, botResult.error ?: "Unknown error")
                commitEvolutionLog("Epoch $epoch: $failureType")
                // Don't merge failed code back to main
                return
            }

            val sharpe = try {
                sharpeCalculator.calculate(
                    startTime = startTime,
                    endTime = endTime,
                    symbol = config.instrument,
                    startingEquity = config.startingEquity,
                )
            } catch (e: Exception) {
                logger.warn(e) { "Sharpe calculation failed, proceeding with zero" }
                null
            }

            logger.info { "Epoch $epoch Sharpe: ${sharpe ?: "insufficient data"}" }
            evolutionLog.append(epoch, startTime, endTime, diff, sharpe ?: BigDecimal.ZERO)

            worktreeManager.commitAndMerge(worktreePath, branchName, "Epoch $epoch: ${config.instrument}")
        } finally {
            worktreeManager.delete(worktreePath, branchName)
        }

        logger.info { "=== Completed Epoch $epoch ===" }
    }

    private fun spawnAgent(worktreePath: Path, epoch: Int) {
        val prompt = buildAgentPrompt(worktreePath, epoch)
        logger.info { "Spawning Claude Code agent in $worktreePath" }
        claudeClient.runPrompt(worktreePath, prompt)
        logger.info { "Agent completed" }
    }

    private fun buildAgentPrompt(worktreePath: Path, epoch: Int): String {
        val lastFailure = evolutionLog.lastFailure()
        val crashContext = when (lastFailure?.failureType) {
            "RISK_BREACH" -> """
            
            CRITICAL: The previous epoch (${lastFailure.epoch}) was KILLED BY RISK CONTROL:
            ```
            ${lastFailure.error}
            ```
            
            Your strategy violated risk limits. This typically means:
            - Strategy is placing orders without checking existing open orders
            - Orders are not being properly tracked or managed
            - The strategy is generating too many actions per tick
            
            You MUST fix this before making any other improvements.
            The changes that caused this:
            ```diff
            ${lastFailure.diff}
            ```
            """
            "BUILD_FAILED", "RUNTIME_CRASH" -> """
            
            CRITICAL: The previous epoch (${lastFailure.epoch}) CRASHED with error:
            ```
            ${lastFailure.error}
            ```
            
            You MUST fix this crash before making any other improvements.
            The crash was caused by:
            ```diff
            ${lastFailure.diff}
            ```
            """
            else -> ""
        }

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

            Read the evolution log to understand the history of changes and their results.
            $crashContext
            TOOLS:
            - `dq epoch --from <start_ms> --to <end_ms>` - Analyze epoch metrics (P&L, fills, risk, Sharpe)

            WORKFLOW:
            1. Read the evolution log to understand past changes and results
            2. Make ONE focused improvement to the strategy
            3. Ensure your strategy has adequate logging (see LOGGING REQUIREMENTS above)
            4. Run `bazel build //...` to verify your changes compile
            5. Fix any build errors before finishing

            This is epoch $epoch. Good luck.
            """.trimIndent()
    }

    private data class BuildResult(val success: Boolean, val bazelBinPath: String? = null, val error: String? = null)
    private data class BotResult(val crashed: Boolean, val exitCode: Int, val error: String? = null)

    private fun build(worktreePath: Path): BuildResult {
        logger.info { "Building bot in $worktreePath" }

        val process = ProcessBuilder("bazel", "build", "//bot")
            .directory(worktreePath.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            logger.error { "Build failed:\n$output" }
            return BuildResult(success = false, error = extractBuildError(output))
        }

        // Get the actual bazel-bin path since Bazel 8 doesn't create bazel-bin symlinks in fresh workspaces
        val infoProcess = ProcessBuilder("bazel", "info", "bazel-bin")
            .directory(worktreePath.toFile())
            .redirectErrorStream(true)
            .start()

        val bazelBinPath = infoProcess.inputStream.bufferedReader().readText().trim()
        val infoExitCode = infoProcess.waitFor()

        if (infoExitCode != 0 || bazelBinPath.isEmpty()) {
            logger.error { "Failed to get bazel-bin path" }
            return BuildResult(success = false, error = "Failed to get bazel-bin path")
        }

        logger.debug { "bazel-bin path: $bazelBinPath" }
        return BuildResult(success = true, bazelBinPath = bazelBinPath)
    }

    private fun extractBuildError(output: String): String {
        val errorLines = output.lines()
            .filter { it.contains("error:") || it.contains("Error:") }
            .take(5)
            .joinToString("\n")
        return errorLines.ifEmpty { "Build failed with unknown error" }
    }

    private fun runBot(worktreePath: Path, bazelBinPath: String, epoch: Int): BotResult {
        logger.info { "Running bot for ${config.epochTradeCount} trades (max ${config.epochMaxDurationMs}ms)" }

        // Create per-epoch log file
        val logDir = config.agentDir.resolve("logs")
        Files.createDirectories(logDir)
        val logFile = logDir.resolve("epoch-$epoch.log")
        val logWriter = Files.newBufferedWriter(logFile)
        logger.info { "Writing bot log to $logFile" }

        // Run the bot launcher directly instead of via `bazel run` to ensure
        // clean process exit propagation. The bot was already built by build().
        val botLauncher = "$bazelBinPath/bot/bot"
        logger.info { "Bot launcher: $botLauncher" }
        logger.info { "Working directory: $worktreePath" }
        val args = buildList {
            add(botLauncher)
            add("--epoch-trades=${config.epochTradeCount}")
            add("--epoch-max-duration=${config.epochMaxDurationMs}")
            add("--strategy=${config.strategyClass}")
            if (config.dryRun) add("--dry-run")
        }
        val processBuilder = ProcessBuilder(args)
            .directory(worktreePath.toFile())
            .redirectErrorStream(true)

        // Clear bazel runfiles env vars so the bot uses its own runfiles, not the harness's
        val env = processBuilder.environment()
        env.remove("JAVA_RUNFILES")
        env.remove("RUNFILES_DIR")
        env.remove("RUNFILES_MANIFEST_FILE")
        env.remove("RUNFILES_MANIFEST_ONLY")
        env.remove("TEST_SRCDIR")

        config.krakenApiKey?.let { env["KRAKEN_API_KEY"] = it }
        config.krakenApiSecret?.let { env["KRAKEN_API_SECRET"] = it }

        val process = processBuilder.start()
        val outputCapture = StringBuilder()

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

        val timeoutMs = config.epochMaxDurationMs + config.gracePeriodMs
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

        if (!completed) {
            logger.warn { "Bot did not exit within timeout, killing process" }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }

        outputThread.join(2000)

        val exitCode = process.exitValue()
        logger.info { "Bot exited with code: $exitCode" }

        return if (exitCode != 0) {
            val error = extractCrashError(outputCapture.toString())
            BotResult(crashed = true, exitCode = exitCode, error = error)
        } else {
            BotResult(crashed = false, exitCode = exitCode)
        }
    }

    private fun extractCrashError(output: String): String {
        val lines = output.lines()
        val exceptionStart = lines.indexOfFirst { it.contains("Exception") || it.contains("Error:") }
        return if (exceptionStart >= 0) {
            lines.drop(exceptionStart).take(15).joinToString("\n")
        } else {
            lines.takeLast(20).joinToString("\n")
        }
    }

    private fun commitEvolutionLog(message: String) {
        val evolutionFile = config.evolutionLogPath.toString()
        val process = ProcessBuilder("git", "add", evolutionFile)
            .directory(config.repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        process.waitFor()

        val statusProcess = ProcessBuilder("git", "diff", "--cached", "--quiet")
            .directory(config.repoRoot.toFile())
            .start()
        val hasChanges = statusProcess.waitFor() != 0

        if (hasChanges) {
            val commitProcess = ProcessBuilder("git", "commit", "-m", message)
                .directory(config.repoRoot.toFile())
                .inheritIO()
                .start()
            commitProcess.waitFor()
            logger.info { "Committed evolution log: $message" }
        }
    }
}
