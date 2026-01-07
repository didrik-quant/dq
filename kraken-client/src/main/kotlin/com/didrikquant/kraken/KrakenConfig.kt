package com.didrikquant.kraken

public data class KrakenConfig(
    val apiKey: String,
    val apiSecret: String,
    val restUrl: String = REST_URL,
    val wsUrl: String = WS_URL,
) {
    public companion object {
        public const val REST_URL: String = "https://futures.kraken.com/derivatives/api/v3"
        public const val WS_URL: String = "wss://futures.kraken.com/ws/v1"

        public fun fromEnv(): KrakenConfig = KrakenConfig(
            apiKey = System.getenv("KRAKEN_API_KEY")
                ?: error("KRAKEN_API_KEY environment variable not set"),
            apiSecret = System.getenv("KRAKEN_API_SECRET")
                ?: error("KRAKEN_API_SECRET environment variable not set"),
        )

        public fun forPublicOnly(): KrakenConfig = KrakenConfig(
            apiKey = "",
            apiSecret = "",
        )
    }
}
