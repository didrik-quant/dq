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
    private val opencodeClient = OpencodeClient(config.opencodeHost, config.opencodePort, config.opencodeModel)

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

    private fun runEpoch() {
        val epoch = evolutionLog.currentEpoch() + 1
        val branchName = "epoch-$epoch-${config.instrument}"

        logger.info { "=== Starting Epoch $epoch ===" }

        val worktreePath = worktreeManager.create(branchName)

        try {
            spawnAgent(worktreePath, epoch)

            val diff = worktreeManager.diff(worktreePath)

            val buildResult = build(worktreePath)
            if (!buildResult.success) {
                logger.error { "Build failed, logging failure and continuing to next epoch" }
                evolutionLog.appendFailure(epoch, diff, "BUILD_FAILED", buildResult.error ?: "Unknown error")
                commitEvolutionLog("Epoch $epoch: BUILD_FAILED")
                return
            }

            val startTime = Instant.now()
            val botResult = runBot(worktreePath)
            val endTime = Instant.now()

            if (botResult.crashed) {
                logger.error { "Bot crashed, logging failure" }
                evolutionLog.appendFailure(epoch, diff, "RUNTIME_CRASH", botResult.error ?: "Unknown error")
                commitEvolutionLog("Epoch $epoch: RUNTIME_CRASH")
                // Don't merge crashed code back to main
                return
            }

            evolutionLog.append(epoch, startTime, endTime, diff, BigDecimal.ZERO)

            worktreeManager.commitAndMerge(worktreePath, branchName, "Epoch $epoch: ${config.instrument}")
        } finally {
            worktreeManager.delete(worktreePath, branchName)
        }

        logger.info { "=== Completed Epoch $epoch ===" }
    }

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

    private fun buildAgentPrompt(worktreePath: Path, epoch: Int): String {
        val lastFailure = evolutionLog.lastFailure()
        val crashContext = if (lastFailure != null) {
            """
            
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
        } else {
            ""
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

            Read the evolution log to understand the history of changes and their results.
            $crashContext
            TOOLS:
            - `dq fills` - View fills from last epoch
            - `dq book --at <timestamp>` - View order book at a specific timestamp

            WORKFLOW:
            1. Read the evolution log to understand past changes and results
            2. Make ONE focused improvement to the strategy
            3. Run `bazel build //...` to verify your changes compile
            4. Fix any build errors before finishing

            This is epoch $epoch. Good luck.
            """.trimIndent()
    }

    private data class BuildResult(val success: Boolean, val error: String? = null)
    private data class BotResult(val crashed: Boolean, val exitCode: Int, val error: String? = null)

    private fun build(worktreePath: Path): BuildResult {
        logger.info { "Building bot in $worktreePath" }

        val process = ProcessBuilder("bazel", "build", "//bot")
            .directory(worktreePath.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return if (exitCode == 0) {
            BuildResult(success = true)
        } else {
            logger.error { "Build failed:\n$output" }
            BuildResult(success = false, error = extractBuildError(output))
        }
    }

    private fun extractBuildError(output: String): String {
        val errorLines = output.lines()
            .filter { it.contains("error:") || it.contains("Error:") }
            .take(5)
            .joinToString("\n")
        return errorLines.ifEmpty { "Build failed with unknown error" }
    }

    private fun runBot(worktreePath: Path): BotResult {
        logger.info { "Running bot for ${config.epochTradeCount} trades (max ${config.epochMaxDurationMs}ms)" }

        val logFile = Files.createTempFile("bot-output", ".log")

        val args = listOf(
            "bazel",
            "run",
            "//bot",
            "--",
            "--epoch-trades=${config.epochTradeCount}",
            "--epoch-max-duration=${config.epochMaxDurationMs}",
            "--strategy=${config.strategyClass}",
        )
        val processBuilder = ProcessBuilder(args)
            .directory(worktreePath.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())

        config.krakenApiKey?.let { processBuilder.environment()["KRAKEN_API_KEY"] = it }
        config.krakenApiSecret?.let { processBuilder.environment()["KRAKEN_API_SECRET"] = it }

        val process = processBuilder.start()
        val timeoutMs = config.epochMaxDurationMs + config.gracePeriodMs
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

        if (!completed) {
            logger.warn { "Bot did not exit within timeout, killing process" }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }

        val exitCode = process.exitValue()
        logger.info { "Bot exited with code: $exitCode" }

        val output = Files.readString(logFile)
        Files.deleteIfExists(logFile)

        return if (exitCode != 0) {
            val error = extractCrashError(output)
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
