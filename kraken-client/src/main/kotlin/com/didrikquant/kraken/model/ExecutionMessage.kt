package com.didrikquant.kraken.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class FillMessage(
    val feed: String,
    val username: String? = null,
    val fills: List<FillEntry> = emptyList(),
)

@Serializable
public data class FillEntry(
    val instrument: String? = null,
    val time: Long? = null,
    val price: Double? = null,
    val seq: Long? = null,
    val buy: Boolean? = null,
    val qty: Double? = null,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("cli_ord_id") val cliOrdId: String? = null,
    @SerialName("fill_id") val fillId: String? = null,
    @SerialName("fill_type") val fillType: String? = null,
    @SerialName("fee_paid") val feePaid: Double? = null,
    @SerialName("fee_currency") val feeCurrency: String? = null,
)

@Serializable
public data class OpenOrdersMessage(
    val feed: String,
    val account: String? = null,
    val orders: List<OpenOrderEntry> = emptyList(),
)

@Serializable
public data class OpenOrderEntry(
    val instrument: String? = null,
    val time: Long? = null,
    @SerialName("last_update_time") val lastUpdateTime: Long? = null,
    val qty: Double? = null,
    val filled: Double? = null,
    @SerialName("limit_price") val limitPrice: Double? = null,
    @SerialName("stop_price") val stopPrice: Double? = null,
    @SerialName("order_type") val orderType: String? = null,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("cli_ord_id") val cliOrdId: String? = null,
    val direction: Int? = null,
    @SerialName("reduce_only") val reduceOnly: Boolean? = null,
)

@Serializable
public data class OpenPositionsMessage(
    val feed: String,
    val account: String? = null,
    val positions: List<PositionEntry> = emptyList(),
)

@Serializable
public data class PositionEntry(
    val instrument: String? = null,
    val balance: Double? = null,
    @SerialName("entry_price") val entryPrice: Double? = null,
    @SerialName("mark_price") val markPrice: Double? = null,
    @SerialName("index_price") val indexPrice: Double? = null,
    val pnl: Double? = null,
    @SerialName("effective_leverage") val effectiveLeverage: Double? = null,
)
