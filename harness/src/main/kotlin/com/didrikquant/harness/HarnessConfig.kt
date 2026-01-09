package com.didrikquant.harness

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

public data class HarnessConfig(
    val instrument: String,
    val epochTradeCount: Int = 50,
    val epochMaxDurationMs: Long = 7_200_000L, // 2 hours safety timeout
    val gracePeriodMs: Long = 60_000L,
    val strategyClass: String,
    val repoRoot: Path,
    val krakenApiKey: String? = null,
    val krakenApiSecret: String? = null,
    val dataDir: Path,
    val startingEquity: BigDecimal,
    val skipAgent: Boolean = false,
    val dryRun: Boolean = false,
) {
    val agentDir: Path get() = repoRoot.resolve("agents").resolve(instrument)
    val evolutionLogPath: Path get() = agentDir.resolve("evolution.md")

    public companion object {
        private val ENV_FILE_PATH: Path = Path.of(System.getProperty("user.home"), ".dq", "harness.env")

        public fun load(): HarnessConfig {
            val props = loadEnvFile()

            fun get(key: String): String? = props.getProperty(key) ?: System.getenv(key)

            val instrument = get("HARNESS_INSTRUMENT") ?: "PF_XRPUSD"
            val epochTradeCount = get("HARNESS_EPOCH_TRADE_COUNT")?.toIntOrNull() ?: 50
            val epochMaxDurationMs = get("HARNESS_EPOCH_MAX_DURATION_MS")?.toLongOrNull() ?: 7_200_000L
            val gracePeriodMs = get("HARNESS_GRACE_PERIOD_MS")?.toLongOrNull() ?: 60_000L
            val strategyClass = get("HARNESS_STRATEGY_CLASS") ?: deriveStrategyClass(instrument)
            val krakenApiKey = get("KRAKEN_API_KEY")
            val krakenApiSecret = get("KRAKEN_API_SECRET")
            val dataDir = get("DATA_DIR")?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".dq", "data")
            val startingEquity = get("STARTING_EQUITY")?.toBigDecimalOrNull()
                ?: BigDecimal(10000)
            val repoRoot = get("HARNESS_REPO_ROOT")?.let { Path.of(it) }
                ?: error("HARNESS_REPO_ROOT must be set in ~/.dq/harness.env")
            val skipAgent = get("HARNESS_SKIP_AGENT")?.toBooleanStrictOrNull() ?: false
            val dryRun = get("HARNESS_DRY_RUN")?.toBooleanStrictOrNull() ?: false

            return HarnessConfig(
                instrument = instrument,
                epochTradeCount = epochTradeCount,
                epochMaxDurationMs = epochMaxDurationMs,
                gracePeriodMs = gracePeriodMs,
                strategyClass = strategyClass,
                repoRoot = repoRoot,
                krakenApiKey = krakenApiKey,
                krakenApiSecret = krakenApiSecret,
                dataDir = dataDir,
                startingEquity = startingEquity,
                skipAgent = skipAgent,
                dryRun = dryRun,
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
