package com.didrikquant.replay.storage

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

private val logger = KotlinLogging.logger {}

public object RetentionManager {

    public fun cleanup(dataDir: Path, retentionDays: Int) {
        if (!Files.exists(dataDir)) return

        val cutoffTime = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)

        val filesToDelete = Files.list(dataDir)
            .toList()
            .filter { it.isRegularFile() }
            .filter { path ->
                try {
                    path.getLastModifiedTime().toInstant().isBefore(cutoffTime)
                } catch (e: Exception) {
                    false
                }
            }

        filesToDelete.forEach { path ->
            try {
                Files.delete(path)
                logger.info { "Deleted old recording file: $path" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete old recording file: $path" }
            }
        }

        if (filesToDelete.isNotEmpty()) {
            logger.info { "Retention cleanup: deleted ${filesToDelete.size} files older than $retentionDays days" }
        }
    }
}
