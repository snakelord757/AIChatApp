package chat

import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

private const val HISTORY_FILE_NAME = "chat-history.json"

class ChatHistoryBusyException : Exception(
    "Не могу продолжить работу, т.к. файл истории чата занят другим процессом. История чата разблокируется при завершении работы программы через функцию exit или завершение процесса"
)

class ChatHistoryStore private constructor(
    private val channel: FileChannel,
    private val lock: FileLock
) : Closeable {
    fun read(): List<ChatMessage> {
        channel.position(0)
        val bytes = ByteBuffer.allocate(channel.size().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
            // Read until the fixed-size buffer is filled or EOF is reached.
        }
        bytes.flip()
        val json = StandardCharsets.UTF_8.decode(bytes).toString().trim()
        if (json.isEmpty()) return emptyList()
        return ChatHistoryJson.decodeMessages(json).map { message ->
            message.copy(content = MojibakeRepair.repair(message.content))
        }
    }

    fun write(messages: List<ChatMessage>) {
        val json = ChatHistoryJson.encodeMessages(messages)
        val bytes = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
        channel.truncate(0)
        channel.position(0)
        while (bytes.hasRemaining()) {
            channel.write(bytes)
        }
        channel.force(true)
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

        fun defaultHistoryPath(): Path = applicationDirectory().resolve(HISTORY_FILE_NAME)

        private fun applicationDirectory(): Path {
            System.getProperty("aichat.history.dir")?.takeIf { it.isNotBlank() }?.let {
                return Paths.get(it).toAbsolutePath().normalize()
            }

            System.getenv("APP_HOME")?.takeIf { it.isNotBlank() }?.let {
                return Paths.get(it).toAbsolutePath().normalize()
            }

            ProcessHandle.current().info().command().orElse(null)?.let { command ->
                val commandPath = Paths.get(command).toAbsolutePath().normalize()
                if (Files.isRegularFile(commandPath)) return commandPath.parent
            }

            val codeSource = ChatHistoryStore::class.java.protectionDomain.codeSource?.location
            if (codeSource != null && codeSource.protocol == "file") {
                val codePath = Paths.get(URI.create(codeSource.toString())).toAbsolutePath().normalize()
                return if (Files.isRegularFile(codePath)) codePath.parent else codePath
            }

            return Paths.get("").toAbsolutePath().normalize()
        }
    }
}
