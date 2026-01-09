package com.didrikquant.harness

import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

public class ClaudeCodeClient {
    private val claudePath: String = findClaudePath()

    public fun runPrompt(workingDirectory: Path, prompt: String) {
        writeSettings(workingDirectory)

        val process = ProcessBuilder(
            claudePath,
            "--model",
            "claude-opus-4-5-20251101",
            "-p",
            prompt
        )
            .directory(workingDirectory.toFile())
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("Claude Code exited with code $exitCode")
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
