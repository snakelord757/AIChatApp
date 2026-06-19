package swarm

import chat.TokenUsage

enum class SwarmRole(
    val displayName: String,
    val temperature: Double,
    val systemPrompt: String
) {
    STRATEGIST(
        displayName = "Strategist",
        temperature = 0.9,
        systemPrompt = """
            You are the Strategist in a planning swarm.
            Propose the solution strategy, the broad approach, and decomposition of the user's goal.
            You see only the StageInput and the swarm dialogue for this PLANNING execution.
            The workingContext was allowed by the orchestrator and may include personal memory and assistant invariants.
            Apply workingContext only when it is directly relevant to the user's current task domain.
            Contribute independent strategic judgment; do not merely agree with another role.
            If another role's idea is good, extend it with a concrete strategic refinement, trade-off, or missing decision.
            Return only JSON with role, stance, summary, proposal, concerns, requiredChanges, and invariantConcerns.
            Do not use Markdown blockquotes or lines starting with the CLI prompt marker.
        """.trimIndent()
    ),
    REQUIREMENTS_ANALYST(
        displayName = "Requirements Analyst",
        temperature = 0.45,
        systemPrompt = """
            You are the Requirements Analyst in a planning swarm.
            Extract requirements, constraints, readiness criteria, missing data, and clarification blockers.
            Treat workingContext as orchestrator-approved context, including personal memory and invariants when available.
            Apply workingContext only when it is directly relevant to the user's current task domain.
            Contribute independent requirements analysis; do not merely agree with another role.
            If the direction is acceptable, still name the concrete requirements and acceptance criteria that make it acceptable.
            Return only JSON with role, stance, summary, proposal, concerns, requiredChanges, and invariantConcerns.
            Use stance block only when the plan is impossible without clarification or violates hard constraints.
        """.trimIndent()
    ),
    SOLUTION_ARCHITECT(
        displayName = "Solution Architect",
        temperature = 0.75,
        systemPrompt = """
            You are the Solution Architect in a planning swarm.
            Define the structure of the plan, decision boundaries, dependencies, and extension path.
            Treat workingContext as orchestrator-approved context, including personal memory and invariants when available.
            Apply workingContext only when it is directly relevant to the user's current task domain.
            Contribute independent architecture analysis; do not merely agree with another role.
            If you approve, include the structural boundary or dependency detail that makes the plan executable.
            Do not invent software components, classes, APIs, pure functions, data models, or interfaces unless the user explicitly asks for software implementation.
            Return only JSON with role, stance, summary, proposal, concerns, requiredChanges, and invariantConcerns.
        """.trimIndent()
    ),
    EXECUTION_COORDINATOR(
        displayName = "Execution Coordinator",
        temperature = 0.5,
        systemPrompt = """
            You are the Execution Coordinator in a planning swarm.
            Turn the agreed direction into an executable plan for the ExecutionAgent.
            Treat workingContext as orchestrator-approved context, including personal memory and invariants when available.
            Apply workingContext only when it is directly relevant to the user's current task domain.
            Contribute independent execution sequencing; do not merely agree with another role.
            If you approve, include ordering, checkpoints, or handoff details that ExecutionAgent can use.
            Return only JSON with role, stance, summary, proposal, concerns, requiredChanges, and invariantConcerns.
        """.trimIndent()
    ),
    RISK_MANAGER(
        displayName = "Risk Manager",
        temperature = 0.85,
        systemPrompt = """
            You are the Risk Manager in a planning swarm.
            Look for risks, invariant violations, memory conflicts, cycles, ambiguities, and unsafe assumptions.
            Treat workingContext as orchestrator-approved context, including personal memory and invariants when available.
            Apply workingContext only when it is directly relevant to the user's current task domain.
            Contribute independent risk analysis; do not merely agree with another role.
            If no blocker exists, still name the risks you checked and any mitigation that should stay in the plan.
            Return only JSON with role, stance, summary, proposal, concerns, requiredChanges, and invariantConcerns.
            Use stance block for blocker objections that make the plan unsafe or impossible.
        """.trimIndent()
    )
}

data class SwarmAgentConfig(
    val role: SwarmRole,
    val minRounds: Int = 1,
    val maxRounds: Int = 3
)

data class SwarmMessage(
    val role: SwarmRole,
    val round: Int,
    val stance: String,
    val summary: String,
    val proposal: String,
    val concerns: List<String> = emptyList(),
    val requiredChanges: List<String> = emptyList(),
    val invariantConcerns: List<String> = emptyList(),
    val rawContent: String,
    val tokenUsage: TokenUsage = TokenUsage.ZERO
) {
    fun toLiveEventText(): String = buildString {
        appendLine("Stage PLANNING swarm message:")
        appendLine("Round $round")
        appendLine("[${role.displayName}] ${summary}")
        if (proposal.isNotBlank()) appendLine(proposal)
        if (concerns.isNotEmpty()) appendLine("Concerns: ${concerns.joinToString("; ")}")
        if (requiredChanges.isNotEmpty()) appendLine("Required changes: ${requiredChanges.joinToString("; ")}")
        if (invariantConcerns.isNotEmpty()) appendLine("Invariant concerns: ${invariantConcerns.joinToString("; ")}")
    }.trim()
}

data class SwarmRound(
    val index: Int,
    val messages: List<SwarmMessage>
)

data class SwarmDialogue(
    val rounds: List<SwarmRound> = emptyList()
) {
    val messages: List<SwarmMessage>
        get() = rounds.flatMap { it.messages }

    fun plus(round: SwarmRound): SwarmDialogue = copy(rounds = rounds + round)

    fun withMessage(message: SwarmMessage): SwarmDialogue {
        val existingIndex = rounds.indexOfFirst { it.index == message.round }
        if (existingIndex < 0) {
            return copy(rounds = rounds + SwarmRound(message.round, listOf(message)))
        }
        val updatedRounds = rounds.toMutableList()
        val existingRound = updatedRounds[existingIndex]
        updatedRounds[existingIndex] = existingRound.copy(messages = existingRound.messages + message)
        return copy(rounds = updatedRounds)
    }

    fun toEventText(): String = buildString {
        appendLine("Stage PLANNING swarm dialogue:")
        rounds.forEach { round ->
            appendLine("Round ${round.index}")
            round.messages.forEach { message ->
                appendLine("[${message.role.displayName}] ${message.summary}")
                if (message.proposal.isNotBlank()) appendLine(message.proposal)
                if (message.concerns.isNotEmpty()) appendLine("Concerns: ${message.concerns.joinToString("; ")}")
                if (message.requiredChanges.isNotEmpty()) appendLine("Required changes: ${message.requiredChanges.joinToString("; ")}")
                if (message.invariantConcerns.isNotEmpty()) appendLine("Invariant concerns: ${message.invariantConcerns.joinToString("; ")}")
            }
        }
    }.trim()
}

data class SwarmRunResult(
    val dialogue: SwarmDialogue,
    val finalJson: String,
    val tokenUsage: TokenUsage
)

data class SwarmSession(
    val taskId: String,
    val dialogue: SwarmDialogue
)

interface SwarmSessionStore {
    fun read(taskId: String): SwarmDialogue?
    fun write(session: SwarmSession)
    fun clear(taskId: String)

    object None : SwarmSessionStore {
        override fun read(taskId: String): SwarmDialogue? = null
        override fun write(session: SwarmSession) = Unit
        override fun clear(taskId: String) = Unit
    }
}

fun interface SwarmEventSink {
    fun emit(content: String)

    object None : SwarmEventSink {
        override fun emit(content: String) = Unit
    }
}

internal object NonCodeSwarmGuard {
    private val softwareArtifactPatterns = listOf(
        Regex("""\b[A-Z][A-Za-z0-9]*(Context|State|Engine|Manager|Registry|Provider|Renderer|Collector|Handler|Config|Service|Controller)\b"""),
        Regex("""\b(data class|class|interface|function|method|API|SDK|JSON object|implementation language|code example|pure function)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(fun\s+\w+|Map<String|List<|TravelContext|TravelPlanState|ScoringEngine|DecisionMethod|DataUpdater|ResultRenderer|CountryFilterEngine)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ExecutionAgent|PlanningAgent|ValidationAgent|CompletionAgent|handoff|checkpoint|architectural constraint|dependency chain|extension gate|output[s]?:|input[s]?:)\b""", RegexOption.IGNORE_CASE),
        Regex("""(класс|интерфейс|метод|функц|модул|код|json-объект|язык реализации|чистые функции|пример кода|агенту исполнения|чекпоинт|контрольная точка|передача данных)""", RegexOption.IGNORE_CASE)
    )

    private val lineRemovalPatterns = listOf(
        Regex("""\b(ExecutionAgent|PlanningAgent|ValidationAgent|CompletionAgent|handoff|JSON|data class|interface|API|SDK|TravelContext|TravelPlanState|ScoringEngine|DecisionMethod|DataUpdater|ResultRenderer|CountryFilterEngine)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b[A-Z][A-Za-z0-9]*(Context|State|Engine|Manager|Registry|Provider|Renderer|Collector|Handler|Config|Service|Controller)\b"""),
        Regex("""(агенту исполнения|чистые функции|json|класс|интерфейс|модул|код)""", RegexOption.IGNORE_CASE)
    )

    fun containsSoftwareArtifacts(message: SwarmMessage): Boolean =
        listOf(
            message.summary,
            message.proposal,
            message.concerns.joinToString("\n"),
            message.requiredChanges.joinToString("\n"),
            message.invariantConcerns.joinToString("\n")
        ).any(::containsSoftwareArtifacts)

    fun containsSoftwareArtifacts(dialogue: SwarmDialogue): Boolean =
        dialogue.messages.any(::containsSoftwareArtifacts)

    fun sanitize(message: SwarmMessage): SwarmMessage {
        val sanitizedSummary = sanitizeText(message.summary)
            .ifBlank { "Contribution kept in the user's task domain." }
        val sanitizedProposal = sanitizeText(message.proposal)
            .ifBlank { "Keep the plan focused on the user's real-world task, without internal implementation details." }
        return message.copy(
            summary = sanitizedSummary,
            proposal = sanitizedProposal,
            concerns = message.concerns.map(::sanitizeText).filter(String::isNotBlank),
            requiredChanges = message.requiredChanges.map(::sanitizeText).filter(String::isNotBlank),
            invariantConcerns = message.invariantConcerns.map(::sanitizeText).filter(String::isNotBlank),
            rawContent = message.rawContent
        )
    }

    fun sanitizeStageJson(content: String): String {
        if (!containsSoftwareArtifacts(content)) return content
        val summary = content.extractString("summary")?.let(::sanitizeText).orEmpty()
        val output = content.extractString("output")?.let(::sanitizeText).orEmpty()
        val safeSummary = summary.ifBlank { "Domain-focused plan" }
        val safeOutput = output.ifBlank { "Prepare a practical plan focused only on the user's real-world task, without internal implementation details." }
        return """{"success":true,"summary":"${agent.JsonTools.escape(safeSummary)}","output":"${agent.JsonTools.escape(safeOutput)}","issues":[],"requestedChanges":[],"retryReason":null}"""
    }

    private fun containsSoftwareArtifacts(value: String): Boolean =
        softwareArtifactPatterns.any { it.containsMatchIn(value) }

    private fun sanitizeText(value: String): String =
        value.lineSequence()
            .filterNot { line -> lineRemovalPatterns.any { it.containsMatchIn(line) } }
            .joinToString("\n")
            .replace(Regex("""\bcheckpoint\b""", RegexOption.IGNORE_CASE), "step")
            .replace(Regex("""\bhandoff\b""", RegexOption.IGNORE_CASE), "next step")
            .replace(Regex("""\barchitectural\b""", RegexOption.IGNORE_CASE), "structural")
            .trim()
}
