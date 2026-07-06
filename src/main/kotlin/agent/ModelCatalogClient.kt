package agent

import mcp.McpJson
import mcp.asArray
import mcp.asObject
import mcp.asString
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class ModelCatalogClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) {
    fun fetch(settings: AgentSettings): List<String> {
        val builder = HttpRequest.newBuilder(endpoint(settings.baseUrl))
            .header("Accept", "application/json")
            .GET()
        if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.apiKey}")
        }

        val response = try {
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (exception: IOException) {
            throw AgentException("Could not load available models from the provider. Check the base URL.", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentException("The model list request was interrupted.", exception)
        } catch (exception: IllegalArgumentException) {
            throw AgentException("Invalid model provider URL: ${settings.baseUrl}", exception)
        }

        if (response.statusCode() !in 200..299) {
            throw AgentException("The model provider returned HTTP ${response.statusCode()} while loading models: ${response.body().take(500)}")
        }

        return parseModelIds(response.body())
    }

    fun parseModelIds(body: String): List<String> {
        val root = runCatching { McpJson.parse(body).asObject() }.getOrNull()
            ?: throw AgentException("The model provider returned an invalid models JSON response.")
        val data = root["data"]?.asArray()
            ?: throw AgentException("The model provider models response is missing data.")
        return data.mapNotNull { model ->
            runCatching {
                model.asObject()?.get("id")?.asString()?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }.distinct()
    }

    private fun endpoint(baseUrl: String): URI =
        URI.create("${baseUrl.trim().trimEnd('/')}/models")
}
