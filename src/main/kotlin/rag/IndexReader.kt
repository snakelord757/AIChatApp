package rag

import mcp.JsonValue
import mcp.McpJson
import mcp.asArray
import mcp.asObject
import mcp.asString
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object IndexReader {
    fun read(path: Path): IndexFile {
        val root = McpJson.parse(Files.readString(path, StandardCharsets.UTF_8)).requiredObject("index")
        val chunks = root.requiredArray("chunks").map { value ->
            val chunk = value.requiredObject("chunk")
            val metadata = chunk.requiredObject("metadata")
            IndexedChunk(
                metadata = ChunkMetadata(
                    source = metadata.requiredString("source"),
                    title = metadata.requiredString("title"),
                    section = metadata["section"]?.asString(),
                    chunkId = metadata.requiredString("chunk_id")
                ),
                text = chunk.requiredString("text"),
                embedding = chunk.requiredArray("embedding").map { number ->
                    (number as? JsonValue.Number)?.raw?.toDoubleOrNull()
                        ?: error("Embedding value must be a number.")
                }
            )
        }
        return IndexFile(
            document = root.requiredString("document"),
            model = root.requiredString("model"),
            strategy = root.requiredString("strategy"),
            chunks = chunks
        )
    }

    private fun JsonValue.requiredObject(name: String): Map<String, JsonValue> =
        asObject() ?: error("JSON field '$name' must be an object.")

    private fun Map<String, JsonValue>.requiredObject(key: String): Map<String, JsonValue> =
        this[key]?.asObject() ?: error("JSON field '$key' must be an object.")

    private fun Map<String, JsonValue>.requiredArray(key: String): List<JsonValue> =
        this[key]?.asArray() ?: error("JSON field '$key' must be an array.")

    private fun Map<String, JsonValue>.requiredString(key: String): String =
        this[key]?.asString() ?: error("JSON field '$key' must be a string.")
}

