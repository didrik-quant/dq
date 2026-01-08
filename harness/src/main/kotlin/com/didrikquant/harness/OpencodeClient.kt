package com.didrikquant.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val logger = KotlinLogging.logger {}

public class OpencodeClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 4096,
    private val model: String = "anthropic/claude-opus-4-5",
) {
    private val baseUrl = "http://$host:$port"
    private val client = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val providerID: String
    private val modelID: String

    init {
        val parts = model.split("/", limit = 2)
        require(parts.size == 2) { "Model must be in format 'provider/model', got: $model" }
        providerID = parts[0]
        modelID = parts[1]
    }

    public fun healthCheck(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/global/health"))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            logger.warn { "Opencode server not reachable: ${e.message}" }
            false
        }
    }

    public fun createSession(): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/session"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("Failed to create session: ${response.body()}")
        }

        val sessionJson = json.parseToJsonElement(response.body()).jsonObject
        return sessionJson["id"]?.jsonPrimitive?.content
            ?: error("No session ID in response: ${response.body()}")
    }

    public fun sendPrompt(sessionId: String, prompt: String) {
        val escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        val body = """{"parts":[{"type":"text","text":"$escapedPrompt"}],""" +
            """"model":{"providerID":"$providerID","modelID":"$modelID"}}"""

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/session/$sessionId/message"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        logger.info { "Sending prompt to opencode session $sessionId (model: $model)" }

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("Failed to send prompt: ${response.body()}")
        }

        val responseJson = json.parseToJsonElement(response.body()).jsonObject
        val errorObj = responseJson["info"]?.jsonObject?.get("error")
        if (errorObj != null) {
            error("Agent returned error: $errorObj")
        }

        logger.info { "Prompt completed successfully" }
    }

    public fun deleteSession(sessionId: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/session/$sessionId"))
            .DELETE()
            .build()

        client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
