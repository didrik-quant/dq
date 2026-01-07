package com.didrikquant.replay.recorder

import java.nio.file.Path

public data class RecorderConfig(
    val dataDir: Path,
    val retentionDays: Int = 30,
    val recordHeartbeats: Boolean = false,
    val compressionLevel: Int = 3,
) {
    public companion object {
        private const val DEFAULT_SUBDIR: String = ".dq/recordings"

        public fun default(): RecorderConfig {
            val home = System.getProperty("user.home")
            return RecorderConfig(
                dataDir = Path.of(home, DEFAULT_SUBDIR),
            )
        }

        public fun fromEnv(): RecorderConfig {
            val dataDir = System.getenv("REPLAY_DATA_DIR")
                ?: "${System.getProperty("user.home")}/$DEFAULT_SUBDIR"
            return RecorderConfig(
                dataDir = Path.of(dataDir),
                retentionDays = System.getenv("REPLAY_RETENTION_DAYS")?.toIntOrNull() ?: 30,
                recordHeartbeats = System.getenv("REPLAY_RECORD_HEARTBEATS")?.toBoolean() ?: false,
                compressionLevel = System.getenv("REPLAY_COMPRESSION_LEVEL")?.toIntOrNull() ?: 3,
            )
        }
    }
}
