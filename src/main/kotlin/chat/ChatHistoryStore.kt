package chat

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ChatHistoryBusyException : Exception(
    "Cannot continue because the chat history file is locked by another process. The history unlocks when that process exits."
)

class ChatHistoryStore private constructor(
    private val channel: FileChannel,
    private val lock: FileLock
) : Closeable {
    fun readState(): ChatHistoryState {
        channel.position(0)
        val bytes = ByteBuffer.allocate(channel.size().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
            // Read until the fixed-size buffer is filled or EOF is reached.
        }
        bytes.flip()
        val json = StandardCharsets.UTF_8.decode(bytes).toString().trim()
        if (json.isEmpty()) return ChatHistoryState()
        val state = ChatHistoryJson.decodeState(json)
        val restoredMessages = state.messages.map { message ->
                message.copy(content = MojibakeRepair.repair(message.content))
            }
        return state.copy(
            messages = addMissingSummaryEvent(restoredMessages, state.summary),
            summary = state.summary?.copy(content = MojibakeRepair.repair(state.summary.content)),
            facts = state.facts.mapValues { (_, value) -> MojibakeRepair.repair(value) },
            branches = state.branches.map { branch ->
                val repairedSummary = branch.summary?.copy(content = MojibakeRepair.repair(branch.summary.content))
                val repairedMessages = branch.messages.map { message ->
                    message.copy(content = MojibakeRepair.repair(message.content))
                }
                branch.copy(
                    name = MojibakeRepair.repair(branch.name),
                    messages = addMissingSummaryEvent(repairedMessages, repairedSummary),
                    summary = repairedSummary,
                    facts = branch.facts.mapValues { (_, value) -> MojibakeRepair.repair(value) }
                )
            },
            checkpoint = state.checkpoint?.copy(
                messages = state.checkpoint.messages.map { message ->
                    message.copy(content = MojibakeRepair.repair(message.content))
                },
                summary = state.checkpoint.summary?.copy(
                    content = MojibakeRepair.repair(state.checkpoint.summary.content)
                ),
                facts = state.checkpoint.facts.mapValues { (_, value) -> MojibakeRepair.repair(value) }
            )
        )
    }

    fun read(): List<ChatMessage> {
        return readState().messages
    }

    fun writeState(state: ChatHistoryState) {
        writeJson(ChatHistoryJson.encodeState(state))
    }

    fun write(messages: List<ChatMessage>) {
        writeJson(ChatHistoryJson.encodeMessages(messages))
    }

    private fun writeJson(json: String) {
        val bytes = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
        channel.truncate(0)
        channel.position(0)
        while (bytes.hasRemaining()) {
            channel.write(bytes)
        }
        channel.force(true)
    }

    private fun addMissingSummaryEvent(messages: List<ChatMessage>, summary: ChatSummary?): List<ChatMessage> {
        if (summary == null) return messages
        val event = ChatMessage(Role.EVENT, summaryUsageMessage(summary.usage))
        if (messages.any { it == event }) return messages

        val insertIndex = (summary.lastMessageIndex + 1).coerceIn(0, messages.size)
        return messages.take(insertIndex) + event + messages.drop(insertIndex)
    }

    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        fun open(path: Path = defaultHistoryPath()): ChatHistoryStore {
            val normalizedPath = path.toAbsolutePath().normalize()
            normalizedPath.parent?.let(Files::createDirectories)
            val channel = FileChannel.open(
                normalizedPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            )
            val lock = try {
                channel.tryLock()
            } catch (exception: OverlappingFileLockException) {
                null
            } catch (exception: IOException) {
                channel.close()
                throw exception
            }

            if (lock == null) {
                channel.close()
                throw ChatHistoryBusyException()
            }

            return ChatHistoryStore(channel, lock)
        }

        fun defaultHistoryPath(): Path = AppPaths.historyPath()
    }
}
