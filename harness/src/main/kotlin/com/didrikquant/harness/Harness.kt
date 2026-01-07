package com.didrikquant.harness

import mu.KotlinLogging
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private val logger = KotlinLogging.logger {}

public class Harness(private val config: HarnessConfig) {

    private val worktreeManager = WorktreeManager(config.repoRoot)
    private val evolutionLog = EvolutionLog(config.evolutionLogPath)
    private val opencodeClient = OpencodeClient(config.opencodeHost, config.opencodePort, config.opencodeModel)

    public fun run() {
        logger.info { "Starting harness for ${config.instrument}" }
        logger.info { "Strategy class: ${config.strategyClass}" }
        logger.info { "Epoch duration: ${config.epochDurationMs}ms" }
        logger.info { "Repo root: ${config.repoRoot}" }
        logger.info { "Opencode server: ${config.opencodeHost}:${config.opencodePort}" }
        logger.info { "Opencode model: ${config.opencodeModel}" }

        Files.createDirectories(config.agentDir)

        if (!opencodeClient.healthCheck()) {
            error("Cannot connect to opencode server at ${config.opencodeHost}:${config.opencodePort}. Start it with: opencode serve --port ${config.opencodePort}")
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
                return
            }

            val startTime = Instant.now()
            runBot(worktreePath)
            val endTime = Instant.now()

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
        return """
            You are a trading strategy developer. Your task is to improve the strategy for ${config.instrument}.
            
            WORKING DIRECTORY: $worktreePath
            
            IMPORTANT FILES:
            - Strategy code: strategy/src/main/kotlin/com/didrikquant/strategy/${config.strategyClass}.kt
            - Evolution log: agents/${config.instrument}/evolution.md

            Read the evolution log to understand the history of changes and their results.

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

    private fun runBot(worktreePath: Path) {
        logger.info { "Running bot for ${config.epochDurationMs}ms" }

        val args = listOf(
            "bazel", "run", "//bot", "--",
            "--epoch-duration=${config.epochDurationMs}",
            "--strategy=${config.strategyClass}",
        )
        val processBuilder = ProcessBuilder(args)
            .directory(worktreePath.toFile())
            .inheritIO()

        config.krakenApiKey?.let { processBuilder.environment()["KRAKEN_API_KEY"] = it }
        config.krakenApiSecret?.let { processBuilder.environment()["KRAKEN_API_SECRET"] = it }

        val process = processBuilder.start()
        val exitCode = process.waitFor()
        logger.info { "Bot exited with code: $exitCode" }
    }
}
