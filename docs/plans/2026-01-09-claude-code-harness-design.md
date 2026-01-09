# Harness: Switch from Opencode to Claude Code

## Overview

Replace the opencode HTTP client in the harness with direct Claude Code CLI invocation.

## Design Decisions

| Decision | Choice |
|----------|--------|
| Invocation method | CLI with `-p` flag |
| Permissions | Harness writes `.claude/settings.json` into each worktree |
| Model | Hardcoded to Opus 4.5 |

## ClaudeCodeClient

Replace `OpencodeClient.kt` with `ClaudeCodeClient.kt`:

```kotlin
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
    }
}
```

## Harness Changes

The `spawnAgent()` method simplifies:

```kotlin
private fun spawnAgent(worktreePath: Path, epoch: Int) {
    val prompt = buildAgentPrompt(worktreePath, epoch)
    logger.info { "Spawning Claude Code agent in $worktreePath" }
    claudeClient.runPrompt(worktreePath, prompt)
    logger.info { "Agent completed" }
}
```

Remove:
- Health check at startup (no server)
- Session create/delete lifecycle
- Opencode logging references

Keep:
- `buildAgentPrompt()` unchanged
- Build, runBot, evolutionLog handling unchanged

## Config Cleanup

Remove from `HarnessConfig.kt`:
- `opencodeHost` field
- `opencodePort` field
- `opencodeModel` field
- Corresponding env var parsing (`OPENCODE_HOST`, `OPENCODE_PORT`, `OPENCODE_MODEL`)

## Permissions

The harness writes locked-down permissions into each worktree:
- Bazel build/test commands allowed
- `dq` CLI tools allowed
- Read any file
- Write/edit only `strategy/` and `agents/` directories

The agent cannot modify build files, core, execution, risk, or other critical directories.

## File Changes

| Action | File |
|--------|------|
| Delete | `harness/.../OpencodeClient.kt` |
| Create | `harness/.../ClaudeCodeClient.kt` |
| Modify | `harness/.../Harness.kt` |
| Modify | `harness/.../HarnessConfig.kt` |
