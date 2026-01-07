package com.didrikquant.replay.player

import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

public data class ReplayConfig(
    val dataDir: Path,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val symbolFilter: String? = null,
) {
    public val startMillis: Long = startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    public val endMillis: Long = endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    public fun matchesSymbol(symbol: String): Boolean {
        return symbolFilter == null || symbolFilter == symbol
    }
}
