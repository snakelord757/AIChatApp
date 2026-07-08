package rag

import mcp.JsonValue
import mcp.McpJson
import mcp.asArray
import mcp.asObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class EmbeddingClient(
    ollamaUrl: String,
    private val model: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) {
    private val endpoint = URI.create("${ollamaUrl.trimEnd('/')}/api/embeddings")

    fun embed(text: String): List<Double> {
        val body = """{"model":"${McpJson.escape(model)}","prompt":"${McpJson.escape(text)}"}"""
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (exception: Exception) {
            throw RuntimeException("Could not create RAG question embedding: ${exception.message}", exception)
        }
        if (response.statusCode() !in 200..299) {
            error("Ollama returned HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }
        val root = McpJson.parse(response.body()).asObject()
            ?: error("Ollama embedding response must be a JSON object.")
        return root["embedding"]?.asArray()
            ?.mapNotNull { (it as? JsonValue.Number)?.raw?.toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: error("Ollama response does not contain a non-empty embedding array.")
    }
}

