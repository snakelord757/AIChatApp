package task

import agent.AgentSettings
import chat.ChatHistoryRepository
import memory.MemoryRepository

interface TaskContextProvider {
    fun contextFor(state: TaskState): String

    object None : TaskContextProvider {
        override fun contextFor(state: TaskState): String = ""
    }
}

class OrchestratorTaskContextProvider(
    private val settingsProvider: () -> AgentSettings,
    private val historyRepository: ChatHistoryRepository,
    private val memoryRepository: MemoryRepository?
) : TaskContextProvider {
    override fun contextFor(state: TaskState): String {
        val settings = settingsProvider()
        val memoryMessages = memoryRepository?.contextMessages().orEmpty()
        val chatContext = historyRepository.apiContextMessages(settings, memoryMessages)
            .joinToString(separator = "\n\n") { message ->
                "[${message.role.apiName}]\n${message.content}"
            }
        return chatContext
    }
}
