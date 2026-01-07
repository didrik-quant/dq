package com.didrikquant.harness

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

public class EvolutionLog(private val logPath: Path) {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

    public fun append(
        epoch: Int,
        startTime: Instant,
        endTime: Instant,
        diff: String,
        sharpe: BigDecimal,
    ) {
        val startStr = formatter.format(startTime)
        val endStr = formatter.format(endTime)

        val entry = buildString {
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Epoch $epoch - $startStr to $endStr UTC")
            appendLine()
            appendLine("### Changes")
            appendLine()
            if (diff.isBlank()) {
                appendLine("No changes")
            } else {
                appendLine("```diff")
                appendLine(diff.trim())
                appendLine("```")
            }
            appendLine()
            appendLine("### Results")
            appendLine()
            appendLine("- Sharpe: $sharpe")
        }

        Files.writeString(
            logPath,
            entry,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    public fun appendFailure(
        epoch: Int,
        diff: String,
        failureType: String,
        errorMessage: String,
    ) {
        val timestamp = formatter.format(Instant.now())

        val entry = buildString {
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Epoch $epoch - FAILED at $timestamp UTC")
            appendLine()
            appendLine("### Failure: $failureType")
            appendLine()
            appendLine("```")
            appendLine(errorMessage.trim())
            appendLine("```")
            appendLine()
            appendLine("### Attempted Changes")
            appendLine()
            if (diff.isBlank()) {
                appendLine("No changes")
            } else {
                appendLine("```diff")
                appendLine(diff.trim())
                appendLine("```")
            }
        }

        Files.writeString(
            logPath,
            entry,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    public fun currentEpoch(): Int {
        if (!Files.exists(logPath)) return 0
        val content = Files.readString(logPath)
        val regex = Regex("""## Epoch (\d+)""")
        return regex.findAll(content).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
    }
}
