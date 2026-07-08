package rag

import java.nio.file.Path

data class ChunkMetadata(
    val source: String,
    val title: String,
    val section: String?,
    val chunkId: String
)

data class IndexedChunk(
    val metadata: ChunkMetadata,
    val text: String,
    val embedding: List<Double>
)

data class IndexFile(
    val document: String,
    val model: String,
    val strategy: String,
    val chunks: List<IndexedChunk>
)

data class LoadedIndex(
    val path: Path,
    val index: IndexFile,
    val embeddingModel: String,
    val embeddingClient: EmbeddingClient
)

data class SearchResult(
    val chunk: IndexedChunk,
    val score: Double
)

data class RagSearchResponse(
    val results: List<SearchResult>,
    val indexCount: Int,
    val chunkCount: Int
)

