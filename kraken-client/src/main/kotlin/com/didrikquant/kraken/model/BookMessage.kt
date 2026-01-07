package com.didrikquant.kraken.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class BookSnapshotMessage(
    val feed: String,
    @SerialName("product_id") val productId: String,
    val timestamp: Long,
    val seq: Long,
    val bids: List<BookEntry>,
    val asks: List<BookEntry>,
)

@Serializable
public data class BookEntry(
    val price: Double,
    val qty: Double,
)

@Serializable
public data class BookDeltaMessage(
    val feed: String,
    @SerialName("product_id") val productId: String,
    val side: String,
    val seq: Long,
    val price: Double,
    val qty: Double,
    val timestamp: Long,
)
