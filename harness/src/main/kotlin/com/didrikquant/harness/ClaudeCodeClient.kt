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
