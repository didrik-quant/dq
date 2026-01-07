package com.didrikquant.kraken.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class WsSubscribeRequest(
    val event: String = "subscribe",
    val feed: String,
    @SerialName("product_ids") val productIds: List<String>,
)

@Serializable
public data class WsAuthSubscribeRequest(
    val event: String = "subscribe",
    val feed: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("original_challenge") val originalChallenge: String,
    @SerialName("signed_challenge") val signedChallenge: String,
)

@Serializable
public data class WsChallengeRequest(
    val event: String = "challenge",
    @SerialName("api_key") val apiKey: String,
)
