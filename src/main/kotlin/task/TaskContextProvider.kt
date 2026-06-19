package task

import agent.AgentSettings
import chat.ChatHistoryRepository
import invariants.InvariantRepository
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
    private val invariantRepository: InvariantRepository? = null,
    private val memoryRepository: MemoryRepository?
) : TaskContextProvider {
    override fun contextFor(state: TaskState): String {
        val settings = settingsProvider()
        val extraSystemMessages = invariantsFor(state.currentStage) +
            memoryRepository?.contextMessages().orEmpty()
        val chatContext = historyRepository.apiContextMessages(settings, extraSystemMessages)
            .joinToString(separator = "\n\n") { message ->
                "[${message.role.apiName}]\n${message.content}"
            }
        return chatContext
    }

    private fun invariantsFor(stage: TaskStage): List<chat.ChatMessage> =
        if (stage == TaskStage.PROMPT_VALIDATION || stage == TaskStage.VALIDATION) {
            invariantRepository?.contextMessages().orEmpty()
        } else {
            emptyList()
        }
}
