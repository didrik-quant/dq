package com.didrikquant.kraken

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

public class KrakenRestClient(private val config: KrakenConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    public suspend fun getAccounts(): JsonObject {
        return authenticatedGet("/accounts")
    }

    public suspend fun getOpenPositions(): JsonObject {
        return authenticatedGet("/openpositions")
    }

    public suspend fun getOpenOrders(): JsonObject {
        return authenticatedGet("/openorders")
    }

    public suspend fun sendOrder(
        symbol: String,
        side: String,
        size: Double,
        price: Double,
        orderType: String = "lmt",
        postOnly: Boolean = true,
        clientOrderId: String? = null,
    ): JsonObject {
        val params = buildString {
            append("orderType=$orderType")
            append("&symbol=$symbol")
            append("&side=$side")
            append("&size=$size")
            append("&limitPrice=$price")
            if (postOnly) append("&postOnly=true")
            clientOrderId?.let { append("&cliOrdId=$it") }
        }
        return authenticatedPost("/sendorder", params)
    }

    public suspend fun cancelOrder(orderId: String): JsonObject {
        return authenticatedPost("/cancelorder", "order_id=$orderId")
    }

    public suspend fun cancelAllOrders(symbol: String? = null): JsonObject {
        val params = symbol?.let { "symbol=$it" } ?: ""
        return authenticatedPost("/cancelallorders", params)
    }

    private suspend fun authenticatedGet(endpoint: String): JsonObject {
        val nonce = KrakenAuth.generateNonce()
        val postData = "nonce=$nonce"
        val authent = KrakenAuth.signRequest(postData, config.apiSecret, nonce)

        val response: HttpResponse = client.get("${config.restUrl}$endpoint") {
            header("APIKey", config.apiKey)
            header("Authent", authent)
            header("Nonce", nonce)
        }

        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun authenticatedPost(endpoint: String, postData: String): JsonObject {
        val nonce = KrakenAuth.generateNonce()
        val fullPostData = if (postData.isEmpty()) "nonce=$nonce" else "$postData&nonce=$nonce"
        val authent = KrakenAuth.signRequest(fullPostData, config.apiSecret, nonce)

        val response: HttpResponse = client.post("${config.restUrl}$endpoint") {
            contentType(ContentType.Application.FormUrlEncoded)
            header("APIKey", config.apiKey)
            header("Authent", authent)
            header("Nonce", nonce)
            setBody(fullPostData)
        }

        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    public fun close() {
        client.close()
    }
}

public class KrakenApiException(message: String) : Exception(message)
