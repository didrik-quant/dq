package com.didrikquant.harness

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

public data class HarnessConfig(
    val instrument: String,
    val epochDurationMs: Long = 3_600_000L,
    val strategyClass: String,
    val repoRoot: Path,
    val opencodeHost: String = "127.0.0.1",
    val opencodePort: Int = 4096,
    val opencodeModel: String = "anthropic/claude-opus-4-5",
    val krakenApiKey: String? = null,
    val krakenApiSecret: String? = null,
) {
    val agentDir: Path get() = repoRoot.resolve("agents").resolve(instrument)
    val evolutionLogPath: Path get() = agentDir.resolve("evolution.md")

    public companion object {
        private val ENV_FILE_PATH: Path = Path.of(System.getProperty("user.home"), ".dq", "harness.env")

        public fun load(): HarnessConfig {
            val props = loadEnvFile()

            fun get(key: String): String? = props.getProperty(key) ?: System.getenv(key)

            val instrument = get("HARNESS_INSTRUMENT") ?: "PF_XRPUSD"
            val epochDurationMs = get("HARNESS_EPOCH_DURATION_MS")?.toLongOrNull() ?: 3_600_000L
            val strategyClass = get("HARNESS_STRATEGY_CLASS") ?: deriveStrategyClass(instrument)
            val opencodeHost = get("OPENCODE_HOST") ?: "127.0.0.1"
            val opencodePort = get("OPENCODE_PORT")?.toIntOrNull() ?: 4096
            val opencodeModel = get("OPENCODE_MODEL") ?: "anthropic/claude-opus-4-5"
            val krakenApiKey = get("KRAKEN_API_KEY")
            val krakenApiSecret = get("KRAKEN_API_SECRET")
            val repoRoot = get("HARNESS_REPO_ROOT")?.let { Path.of(it) }
                ?: error("HARNESS_REPO_ROOT must be set in ~/.dq/harness.env")

            return HarnessConfig(
                instrument = instrument,
                epochDurationMs = epochDurationMs,
                strategyClass = strategyClass,
                repoRoot = repoRoot,
                opencodeHost = opencodeHost,
                opencodePort = opencodePort,
                opencodeModel = opencodeModel,
                krakenApiKey = krakenApiKey,
                krakenApiSecret = krakenApiSecret,
            )
        }

        public fun loadEnvFile(): Properties {
            val props = Properties()
            if (Files.exists(ENV_FILE_PATH)) {
                Files.newBufferedReader(ENV_FILE_PATH).use { reader ->
                    reader.lineSequence()
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .forEach { line ->
                            val idx = line.indexOf('=')
                            if (idx > 0) {
                                val key = line.substring(0, idx).trim()
                                val value = line.substring(idx + 1).trim().removeSurrounding("\"")
                                props.setProperty(key, value)
                            }
                        }
                }
            }
            return props
        }

        private fun deriveStrategyClass(instrument: String): String {
            val base = instrument.replace("PF_", "").replace("USD", "")
            return "Agent${base.lowercase().replaceFirstChar { it.uppercase() }}Strategy"
        }
    }
}
