package com.didrikquant.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

public class ClaudeCodeClient {
    private val claudePath: String = findClaudePath()

    public fun runPrompt(workingDirectory: Path, prompt: String) {
        writeSettings(workingDirectory)

        val process = ProcessBuilder(
            claudePath,
            "--model",
            "claude-opus-4-5-20251101",
            "-p",
            prompt,
            "--output-format",
            "stream-json",
            "--verbose"
        )
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()

        val outputThread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                parseAndLogEvent(line)
            }
        }.apply {
            isDaemon = true
            name = "claude-output-reader"
            start()
        }

        val exitCode = process.waitFor()
        outputThread.join(2000)

        if (exitCode != 0) {
            error("Claude Code exited with code $exitCode")
        }
    }

    private fun parseAndLogEvent(line: String) {
        if (!line.startsWith("{")) {
            logger.info { "[CLAUDE] $line" }
            return
        }

        try {
            val event = json.parseToJsonElement(line).jsonObject
            val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
            val subtype = event["subtype"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "system" -> {
                    when (subtype) {
                        "init" -> logger.info { "[CLAUDE] Session started" }
                    }
                }
                "assistant" -> {
                    val message = event["message"]?.jsonObject
                    val content = message?.get("content")?.jsonArray
                    content?.forEach { block ->
                        val blockObj = block.jsonObject
                        when (blockObj["type"]?.jsonPrimitive?.contentOrNull) {
                            "text" -> {
                                val text = blockObj["text"]?.jsonPrimitive?.contentOrNull
                                text?.lines()?.forEach { textLine ->
                                    if (textLine.isNotBlank()) {
                                        logger.info { "[CLAUDE] $textLine" }
                                    }
                                }
                            }
                            "tool_use" -> {
                                val toolName = blockObj["name"]?.jsonPrimitive?.contentOrNull
                                logger.info { "[CLAUDE] Using tool: $toolName" }
                            }
                        }
                    }
                }
                "user" -> {
                    val content = event["message"]?.jsonObject?.get("content")?.jsonArray
                    content?.forEach { block ->
                        val blockObj = block.jsonObject
                        if (blockObj["type"]?.jsonPrimitive?.contentOrNull == "tool_result") {
                            val toolUseId = blockObj["tool_use_id"]?.jsonPrimitive?.contentOrNull
                            logger.debug { "[CLAUDE] Tool result received" }
                        }
                    }
                }
                "result" -> {
                    val cost = event["total_cost_usd"]?.jsonPrimitive?.contentOrNull
                    val duration = event["duration_ms"]?.jsonPrimitive?.contentOrNull
                    logger.info { "[CLAUDE] Completed (cost: $$cost, duration: ${duration}ms)" }
                }
            }
        } catch (e: Exception) {
            logger.debug { "[CLAUDE] Parse error: ${e.message}" }
        }
    }

    private fun findClaudePath(): String {
        System.getenv("CLAUDE_PATH")?.let {
            logger.info { "Using CLAUDE_PATH: $it" }
            return it
        }

        val candidates = listOf(
            System.getProperty("user.home") + "/.claude/local/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            "/usr/bin/claude"
        )

        for (path in candidates) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                logger.info { "Found Claude CLI at: $path" }
                return path
            }
        }

        error(
            "Claude CLI not found. Checked: ${candidates.joinToString()}\n" +
                "Install Claude Code or set CLAUDE_PATH environment variable."
        )
    }

    private fun writeSettings(worktreePath: Path) {
        val settings =
            """
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
