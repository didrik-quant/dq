package com.didrikquant.harness

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

public class WorktreeManager(private val repoRoot: Path) {

    private val worktreesDir: Path = repoRoot.resolve(".harness-worktrees")

    public fun create(branchName: String): Path {
        Files.createDirectories(worktreesDir)
        val worktreePath = worktreesDir.resolve(branchName)

        if (Files.exists(worktreePath)) {
            logger.warn { "Worktree already exists at $worktreePath, removing first" }
            delete(worktreePath, branchName)
        }

        logger.info { "Creating worktree: $branchName at $worktreePath" }

        exec(repoRoot, "git", "worktree", "add", "-b", branchName, worktreePath.toString(), "main")

        return worktreePath
    }

    public fun diff(worktreePath: Path): String {
        exec(worktreePath, "git", "add", "-A")
        return execOutput(worktreePath, "git", "diff", "--cached")
    }

    public fun commitAndMerge(worktreePath: Path, branchName: String, message: String) {
        exec(worktreePath, "git", "add", "-A")

        val status = execOutput(worktreePath, "git", "status", "--porcelain")
        if (status.isBlank()) {
            logger.info { "No changes to commit" }
        } else {
            exec(worktreePath, "git", "commit", "-m", message)
        }

        exec(repoRoot, "git", "checkout", "main")
        exec(repoRoot, "git", "merge", branchName, "--no-edit")

        logger.info { "Merged $branchName into main" }
    }

    public fun delete(worktreePath: Path, branchName: String) {
        exec(repoRoot, "git", "worktree", "remove", worktreePath.toString(), "--force")
        exec(repoRoot, "git", "branch", "-D", branchName)
        logger.info { "Deleted worktree and branch: $branchName" }
    }

    private fun exec(workDir: Path, vararg command: String) {
        logger.debug { "Executing: ${command.joinToString(" ")} in $workDir" }
        val process = ProcessBuilder(*command)
            .directory(workDir.toFile())
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("Command failed with exit code $exitCode: ${command.joinToString(" ")}")
        }
    }

    private fun execOutput(workDir: Path, vararg command: String): String {
        logger.debug { "Executing: ${command.joinToString(" ")} in $workDir" }
        val process = ProcessBuilder(*command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("Command failed with exit code $exitCode: ${command.joinToString(" ")}\n$output")
        }
        return output
    }
}
