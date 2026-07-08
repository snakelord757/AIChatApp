package rag

import agent.AgentSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.name

class RagSearchService(
    private val indicesDirectory: Path
) {
    private val executor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    ) { task -> Thread(task, "rag-search").apply { isDaemon = true } }

    fun search(question: String, settings: AgentSettings): RagSearchResponse {
        val indexes = loadIndexes(settings)
        if (indexes.isEmpty()) {
            return RagSearchResponse(emptyList(), indexCount = 0, chunkCount = 0)
        }
        val chunkCount = indexes.sumOf { it.index.chunks.size }
        if (chunkCount == 0 || settings.ragSearchTopK <= 0 || settings.ragTopK <= 0) {
            return RagSearchResponse(emptyList(), indexes.size, chunkCount)
        }

        val searchTopK = settings.ragSearchTopK.coerceAtLeast(settings.ragTopK)
        val tasks = indexes.map { loaded ->
            Callable {
                val questionEmbedding = loaded.embeddingClient.embed(question)
                topKByScore(loaded.index.chunks, questionEmbedding, searchTopK)
            }
        }
        val partialResults = executor.invokeAll(tasks).flatMap { it.get() }
        return RagSearchResponse(
            results = topKResults(partialResults, settings.ragTopK),
            indexCount = indexes.size,
            chunkCount = chunkCount
        )
    }

    private fun loadIndexes(settings: AgentSettings): List<LoadedIndex> {
        if (!Files.isDirectory(indicesDirectory)) return emptyList()
        val paths = Files.list(indicesDirectory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.name.endsWith("-index.json", ignoreCase = true) }
                .map { it.toAbsolutePath().normalize() }
                .sorted()
                .toList()
        }
        return paths.map { path ->
            val index = IndexReader.read(path)
            val embeddingModel = settings.ragEmbeddingModel?.takeIf { it.isNotBlank() } ?: index.model
            LoadedIndex(
                path = path,
                index = index,
                embeddingModel = embeddingModel,
                embeddingClient = EmbeddingClient(settings.ragOllamaUrl, embeddingModel)
            )
        }
    }

    private fun topKByScore(
        chunks: List<IndexedChunk>,
        questionEmbedding: List<Double>,
        topK: Int
    ): List<SearchResult> {
        val queue = PriorityQueue<SearchResult>(compareBy<SearchResult> { it.score })
        chunks.forEach { chunk ->
            val result = SearchResult(chunk, cosine(questionEmbedding, chunk.embedding))
            if (queue.size < topK) {
                queue += result
            } else if (result.score > queue.peek().score) {
                queue.poll()
                queue += result
            }
        }
        return queue.sortedByDescending { it.score }
    }

    private fun topKResults(results: List<SearchResult>, topK: Int): List<SearchResult> {
        val queue = PriorityQueue<SearchResult>(compareBy<SearchResult> { it.score })
        results.forEach { result ->
            if (queue.size < topK) {
                queue += result
            } else if (result.score > queue.peek().score) {
                queue.poll()
                queue += result
            }
        }
        return queue.sortedByDescending { it.score }
    }

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until size) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0.0 || normB == 0.0) 0.0 else dot / kotlin.math.sqrt(normA * normB)
    }
}

