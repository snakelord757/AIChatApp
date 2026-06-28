package task

import mcp.JsonValue
import mcp.McpJson
import mcp.McpToolArgumentSanitizer
import mcp.McpToolCallResult
import mcp.McpToolGateway
import mcp.asArray
import mcp.asObject
import mcp.asString
import java.util.concurrent.Callable
import java.util.concurrent.Executors

enum class ToolChainExecutionMode {
    SEQUENTIAL,
    PARALLEL
}

data class PlannedToolExecutionPlan(
    val chains: List<PlannedToolChain>,
    val allowParallel: Boolean = true
)

data class PlannedToolChain(
    val id: String,
    val mode: ToolChainExecutionMode,
    val dependsOn: List<String>,
    val inputJson: String,
    val steps: List<PlannedToolStep>
)

data class PlannedToolStep(
    val id: String,
    val serverName: String,
    val toolName: String,
    val argumentsJson: String,
    val dependsOn: List<String>,
    val inputMappings: Map<String, String>
)

data class ToolPipelineRunResult(
    val success: Boolean,
    val summary: String,
    val output: String,
    val events: List<String>
)

class ToolExecutionPipeline(
    private val gateway: McpToolGateway
) {
    fun run(planJson: String, onEvent: (String) -> Unit = {}): ToolPipelineRunResult {
        val events = mutableListOf<String>()
        val eventLock = Any()
        fun emit(content: String) {
            synchronized(eventLock) {
                events += content
                onEvent(content)
            }
        }

        val plan = try {
            ToolExecutionPlanParser.parse(planJson)
        } catch (exception: RuntimeException) {
            val message = exception.message ?: "Invalid tool execution plan."
            emit("Tool pipeline rejected: $message")
            return ToolPipelineRunResult(false, "Tool execution plan rejected.", message, events)
        }

        val validation = try {
            validate(plan)
        } catch (exception: RuntimeException) {
            listOf(exception.message ?: "Could not validate tool execution plan.")
        }
        if (validation.isNotEmpty()) {
            val message = validation.joinToString("; ")
            emit("Tool pipeline rejected: $message")
            return ToolPipelineRunResult(false, "Tool execution plan rejected.", message, events)
        }

        val executor = Executors.newCachedThreadPool()
        val completedChains = linkedMapOf<String, CompletedChain>()
        val pending = plan.chains.associateBy { it.id }.toMutableMap()
        try {
            while (pending.isNotEmpty()) {
                val ready = pending.values
                    .filter { chain -> chain.dependsOn.all(completedChains::containsKey) }
                    .sortedBy { chain -> plan.chains.indexOf(chain) }
                if (ready.isEmpty()) {
                    val unresolved = pending.keys.joinToString(", ")
                    val message = "No executable tool chains remain. Unresolved chains: $unresolved"
                    emit("Tool pipeline failed: $message")
                    return ToolPipelineRunResult(false, "Tool execution pipeline failed.", message, events)
                }

                val runnable = if (plan.allowParallel) ready else ready.take(1)
                val futures = runnable.map { chain ->
                    executor.submit(Callable {
                        executeChain(chain, completedChains.toMap(), emit = ::emit)
                    })
                }
                runnable.zip(futures).forEach { (chain, future) ->
                    val completed = try {
                        future.get()
                    } catch (exception: RuntimeException) {
                        CompletedChain.failed(chain.id, exception.message ?: "Tool chain failed.")
                    } catch (exception: Exception) {
                        CompletedChain.failed(chain.id, exception.message ?: "Tool chain failed.")
                    }
                    completedChains[chain.id] = completed
                    pending.remove(chain.id)
                    if (!completed.success) {
                        val output = pipelineOutput(completedChains.values.toList())
                        return ToolPipelineRunResult(false, "Tool execution pipeline failed.", output, events)
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }

        val output = pipelineOutput(completedChains.values.toList())
        return ToolPipelineRunResult(true, "Tool execution pipeline completed.", output, events)
    }

    private fun executeChain(
        chain: PlannedToolChain,
        completedChains: Map<String, CompletedChain>,
        emit: (String) -> Unit
    ): CompletedChain {
        val resolvedChain = try {
            resolveChainInput(chain, completedChains)
        } catch (exception: RuntimeException) {
            val message = exception.message ?: "Could not resolve tool chain input."
            emit("Tool chain failed before MCP call: ${chain.id}: $message")
            return CompletedChain.failed(chain.id, message)
        }
        emit("Tool chain '${resolvedChain.id}' started in ${resolvedChain.mode.name.lowercase()} mode")
        emit("Tool chain '${resolvedChain.id}' received input: ${resolvedChain.inputJson}")
        resolvedChain.dependsOn.takeIf { it.isNotEmpty() }?.let {
            emit("Tool chain '${resolvedChain.id}' depends on: ${it.joinToString(", ")}")
        }

        val completedSteps = linkedMapOf<String, CompletedStep>()
        val pending = resolvedChain.steps.associateBy { it.id }.toMutableMap()
        val executor = Executors.newCachedThreadPool()
        try {
            while (pending.isNotEmpty()) {
                val ready = pending.values.filter { step -> step.dependsOn.all(completedSteps::containsKey) }
                if (ready.isEmpty()) {
                    val message = "No executable steps remain in chain '${resolvedChain.id}'."
                    emit("Tool chain failed: $message")
                    return CompletedChain.failed(resolvedChain.id, message)
                }
                val runnable = if (resolvedChain.mode == ToolChainExecutionMode.PARALLEL) ready else ready.take(1)
                val futures = runnable.map { step ->
                    executor.submit(Callable {
                        executeStep(resolvedChain, step, completedChains, completedSteps.toMap(), emit)
                    })
                }
                runnable.zip(futures).forEach { (step, future) ->
                    val completed = try {
                        future.get()
                    } catch (exception: RuntimeException) {
                        CompletedStep.failed(step.id, step.serverName, step.toolName, exception.message ?: "Tool step failed.")
                    } catch (exception: Exception) {
                        CompletedStep.failed(step.id, step.serverName, step.toolName, exception.message ?: "Tool step failed.")
                    }
                    completedSteps[step.id] = completed
                    pending.remove(step.id)
                    if (!completed.success) {
                        val message = completed.errorMessage ?: "Tool step '${step.id}' failed."
                        emit("Tool chain failed: $message")
                        return CompletedChain(resolvedChain.id, success = false, steps = completedSteps.values.toList(), errorMessage = message)
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }

        emit("Tool chain completed: success")
        return CompletedChain(resolvedChain.id, success = true, steps = completedSteps.values.toList())
    }

    private fun executeStep(
        chain: PlannedToolChain,
        step: PlannedToolStep,
        completedChains: Map<String, CompletedChain>,
        completedSteps: Map<String, CompletedStep>,
        emit: (String) -> Unit
    ): CompletedStep {
        step.dependsOn.takeIf { it.isNotEmpty() }?.let {
            emit("Tool step '${step.id}' depends on: ${it.joinToString(", ")}")
        }
        val argumentsJson = try {
            resolveArguments(chain, step, completedChains, completedSteps)
        } catch (exception: RuntimeException) {
            val message = exception.message ?: "Could not resolve MCP tool arguments."
            emit("Tool step failed before MCP call: ${step.serverName}/${step.toolName}: $message")
            return CompletedStep.failed(step.id, step.serverName, step.toolName, message)
        }
        emit("Tool step '${step.id}' received input: $argumentsJson")
        val result = try {
            gateway.callTool(step.serverName, step.toolName, argumentsJson)
        } catch (exception: RuntimeException) {
            val message = exception.message ?: "Could not call MCP tool."
            emit("Tool step failed: ${step.serverName}/${step.toolName}: $message")
            return CompletedStep.failed(step.id, step.serverName, step.toolName, message, argumentsJson)
        }
        return if (result.isError) {
            val content = normalizedToolContent(result.content)
            emit("Tool step failed: ${step.serverName}/${step.toolName}: ${content.take(500)}")
            CompletedStep.fromResult(step.id, argumentsJson, result.copy(content = content), success = false)
        } else {
            val content = normalizedToolContent(result.content)
            emit("Tool step '${step.id}' called ${step.serverName}/${step.toolName}: success. Result: ${content.take(500)}")
            CompletedStep.fromResult(step.id, argumentsJson, result.copy(content = content), success = true)
        }
    }

    private fun resolveArguments(
        chain: PlannedToolChain,
        step: PlannedToolStep,
        completedChains: Map<String, CompletedChain>,
        completedSteps: Map<String, CompletedStep>
    ): String {
        val base = McpJson.parse(step.argumentsJson).asObject()?.toMutableMap()
            ?: linkedMapOf<String, JsonValue>()
        step.inputMappings.forEach { (target, source) ->
            val value = resolveMapping(source, target, chain, completedChains, completedSteps)
            if (value.isBlank()) {
                error("Input mapping for '$target' resolved to blank from '$source'")
            }
            base[target] = JsonValue.StringValue(value)
        }
        val tool = gateway.availableTools().firstOrNull { it.serverName == step.serverName && it.name == step.toolName }
        return McpToolArgumentSanitizer.sanitize(McpJson.stringify(JsonValue.ObjectValue(base)), tool)
    }

    private fun resolveChainInput(
        chain: PlannedToolChain,
        completedChains: Map<String, CompletedChain>
    ): PlannedToolChain {
        val input = McpJson.parse(chain.inputJson).asObject()?.toMutableMap() ?: return chain
        var changed = false
        input.replaceAll { key, value ->
            val expression = value.asString()
            if (expression != null && looksLikeMappingExpression(expression)) {
                changed = true
                JsonValue.StringValue(resolveMapping(expression, key, chain, completedChains, emptyMap()))
            } else {
                value
            }
        }
        return if (changed) chain.copy(inputJson = McpJson.stringify(JsonValue.ObjectValue(input))) else chain
    }

    private fun looksLikeMappingExpression(value: String): Boolean =
        mappingTerms(value).any { term ->
            val normalized = normalizeMappingTerm(term)
            resolveLiteral(term) != null ||
                normalized.startsWith("chain.input.") ||
                normalized.startsWith("steps.") ||
                normalized.startsWith("chains.")
        }

    private fun resolveMapping(
        source: String,
        target: String,
        chain: PlannedToolChain,
        completedChains: Map<String, CompletedChain>,
        completedSteps: Map<String, CompletedStep>
    ): String {
        val terms = mappingTerms(source)
        if (terms.size > 1) {
            return terms.joinToString("") { term ->
                resolveLiteral(term) ?: resolveSource(normalizeMappingTerm(term), chain, completedChains, completedSteps)
            }
        }
        return coerceMappedValue(target, resolveSource(normalizeMappingTerm(source), chain, completedChains, completedSteps))
    }

    private fun resolveSource(
        source: String,
        chain: PlannedToolChain,
        completedChains: Map<String, CompletedChain>,
        completedSteps: Map<String, CompletedStep>
    ): String {
        if (source.startsWith("chain.input.")) {
            val path = source.removePrefix("chain.input.").split(".")
            return McpJson.parse(chain.inputJson).valueAt(path)
        }
        if (source.startsWith("steps.")) {
            val parts = source.split(".")
            val stepId = parts.getOrNull(1)
            val step = completedSteps[stepId] ?: completedChains.values
                .flatMap { it.steps }
                .firstOrNull { it.id == stepId }
                ?: return ""
            val selector = parts.getOrNull(2).orEmpty()
            val value = when (selector.substringBefore("[")) {
                "content", "output" -> step.content
                "input" -> step.argumentsJson
                else -> ""
            }
            return value.valueAt(selector.indexPathPrefix() + parts.drop(3))
        }
        if (source.startsWith("chains.")) {
            val parts = source.split(".")
            val completed = completedChains[parts.getOrNull(1)] ?: return ""
            if (parts.getOrNull(2) == "steps") {
                val stepId = parts.getOrNull(3) ?: return ""
                val step = completed.steps.firstOrNull { it.id == stepId } ?: return ""
                val selector = parts.getOrNull(4).orEmpty()
                val value = when (selector.substringBefore("[")) {
                    "content", "output" -> step.content
                    "input" -> step.argumentsJson
                    else -> ""
                }
                return value.valueAt(selector.indexPathPrefix() + parts.drop(5))
            }
            val value = when (parts.getOrNull(2)) {
                "output", "content" -> completed.steps.joinToString("\n") { it.content }
                else -> ""
            }
            return value.valueAt(parts.drop(3))
        }
        return ""
    }

    private fun coerceMappedValue(target: String, value: String): String {
        if (value.isBlank()) return value
        val normalized = normalizedToolContent(value)
        val root = runCatching { McpJson.parse(normalized) }.getOrNull() ?: return normalized
        if (target == "id" || target == "amiiboId") {
            root.syntheticAmiiboId()?.let { return it }
        }
        return root.asObject()?.get(target)?.scalarString() ?: normalized
    }

    private fun String.valueAt(path: List<String>): String {
        val normalized = normalizedToolContent(this)
        if (path.isEmpty()) return normalized
        val value = runCatching { McpJson.parse(normalized).valueAt(path) }.getOrNull()
        return value ?: normalized
    }

    private fun normalizedToolContent(content: String): String {
        val root = runCatching { McpJson.parse(content).asObject() }.getOrNull() ?: return content
        val text = root["content"]?.asArray().orEmpty().mapNotNull { item ->
            val objectValue = item.asObject() ?: return@mapNotNull null
            objectValue["text"]?.asString()
                ?: objectValue["data"]?.let(McpJson::stringify)
        }.joinToString("\n")
        return text.ifBlank { content }
    }

    private fun JsonValue.valueAt(path: List<String>): String {
        var current: JsonValue = this
        path.forEach { key ->
            if (key.isBlank()) return@forEach
            current = when (current) {
                is JsonValue.ArrayValue -> {
                    val index = key.toIntOrNull() ?: 0
                    current.values.getOrNull(index) ?: return ""
                }
                else -> current.asObject()?.get(key)
                    ?: current.syntheticValueFor(key)
                    ?: return ""
            }
        }
        if (path.lastOrNull() == "id" || path.lastOrNull() == "amiiboId") {
            current.syntheticAmiiboId()?.let { return it }
        }
        return current.scalarString()
    }

    private fun String.indexPathPrefix(): List<String> {
        val start = indexOf('[')
        val end = indexOf(']', startIndex = (start + 1).coerceAtLeast(0))
        if (start < 0 || end <= start) return emptyList()
        return listOf(substring(start + 1, end))
    }

    private fun JsonValue.syntheticAmiiboId(): String? {
        val objectValue = when (this) {
            is JsonValue.ObjectValue -> values
            is JsonValue.ArrayValue -> values.firstOrNull()?.asObject()
            else -> null
        } ?: return null
        val head = objectValue["head"]?.asString()?.takeIf { it.length == 8 }
        val tail = objectValue["tail"]?.asString()?.takeIf { it.length == 8 }
        return if (head != null && tail != null) head + tail else objectValue["id"]?.asString()
    }

    private fun JsonValue.syntheticValueFor(key: String): JsonValue? =
        when (key) {
            "id", "amiiboId" -> syntheticAmiiboId()?.let(JsonValue::StringValue)
            "games" -> combinedAmiiboGames()
            else -> null
        }

    private fun JsonValue.combinedAmiiboGames(): JsonValue? {
        val objectValue = asObject() ?: return null
        val games = listOf("games3DS", "gamesSwitch", "gamesWiiU")
            .flatMap { field -> objectValue[field]?.asArray().orEmpty() }
        return games.takeIf { it.isNotEmpty() }?.let(JsonValue::ArrayValue)
    }

    private fun JsonValue.scalarString(): String =
        when (this) {
            JsonValue.Null -> ""
            is JsonValue.Bool -> value.toString()
            is JsonValue.Number -> raw
            is JsonValue.StringValue -> value
            is JsonValue.ArrayValue,
            is JsonValue.ObjectValue -> McpJson.stringify(this)
        }

    private fun validateMappingSources(step: PlannedToolStep, source: String, errors: MutableList<String>) {
        mappingTerms(source).forEach { term ->
            val normalized = normalizeMappingTerm(term)
            if (
                resolveLiteral(term) == null &&
                !normalized.startsWith("chain.input.") &&
                !normalized.startsWith("steps.") &&
                !normalized.startsWith("chains.")
            ) {
                errors += "Step '${step.id}' has unsupported input mapping source '$source'"
            }
        }
    }

    private fun mappingTerms(source: String): List<String> =
        source.split("+")
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { listOf(source.trim()) }

    private fun normalizeMappingTerm(term: String): String {
        val trimmed = term.trim()
        if (!trimmed.startsWith("JSON.stringify(") || !trimmed.endsWith(")")) return trimmed
        return trimmed.removePrefix("JSON.stringify(").dropLast(1).trim()
    }

    private fun resolveLiteral(term: String): String? {
        if (term.length < 2) return null
        val quote = term.first()
        if ((quote != '"' && quote != '\'') || term.last() != quote) return null
        return term.substring(1, term.length - 1)
    }

    private fun validate(plan: PlannedToolExecutionPlan): List<String> {
        val errors = mutableListOf<String>()
        if (plan.chains.isEmpty()) errors += "toolExecutionPlan.chains must not be empty"
        val chainIds = mutableSetOf<String>()
        plan.chains.forEach { chain ->
            if (!chainIds.add(chain.id)) errors += "Duplicate chain id '${chain.id}'"
            if (chain.id.isBlank()) errors += "Chain id must not be blank"
            if (chain.mode == ToolChainExecutionMode.PARALLEL && !plan.allowParallel) {
                errors += "Chain '${chain.id}' uses parallel mode while constraints.allowParallel is false"
            }
            if (chain.steps.isEmpty()) errors += "Chain '${chain.id}' must include at least one step"
            validateSteps(chain, errors)
        }
        plan.chains.forEach { chain ->
            chain.dependsOn.forEach { dependency ->
                if (dependency !in chainIds) errors += "Chain '${chain.id}' depends on unknown chain '$dependency'"
            }
            chain.steps.flatMap { it.inputMappings.values }
                .filter { it.startsWith("chains.") }
                .mapNotNull { it.split(".").getOrNull(1) }
                .forEach { sourceChain ->
                    if (sourceChain !in chain.dependsOn) {
                        errors += "Chain '${chain.id}' maps data from chain '$sourceChain' but does not depend on it"
                    }
                }
        }
        errors += validateChainCycles(plan)
        return errors
    }

    private fun validateSteps(chain: PlannedToolChain, errors: MutableList<String>) {
        val available = gateway.availableTools().map { "${it.serverName}/${it.name}" }.toSet()
        val allStepIds = chain.steps.map { it.id }.toSet()
        val ids = mutableSetOf<String>()
        chain.steps.forEachIndexed { index, step ->
            if (step.id.isBlank()) errors += "Step id in chain '${chain.id}' must not be blank"
            if (!ids.add(step.id)) errors += "Duplicate step id '${step.id}' in chain '${chain.id}'"
            if ("${step.serverName}/${step.toolName}" !in available) {
                errors += "Step '${step.id}' references unavailable tool ${step.serverName}/${step.toolName}"
            }
            step.dependsOn.forEach { dependency ->
                if (dependency !in allStepIds) errors += "Step '${step.id}' depends on unknown step '$dependency'"
                if (dependency == step.id) errors += "Step '${step.id}' cannot depend on itself"
            }
            step.inputMappings.values.forEach { source ->
                validateMappingSources(step, source, errors)
            }
        }
        errors += validateStepCycles(chain)
    }

    private fun validateChainCycles(plan: PlannedToolExecutionPlan): List<String> {
        val byId = plan.chains.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            if (!visiting.add(id)) {
                errors += "Chain dependency cycle includes '$id'"
                return
            }
            byId[id]?.dependsOn.orEmpty().forEach(::visit)
            visiting.remove(id)
            visited += id
        }
        plan.chains.forEach { visit(it.id) }
        return errors
    }

    private fun validateStepCycles(chain: PlannedToolChain): List<String> {
        val byId = chain.steps.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            if (!visiting.add(id)) {
                errors += "Step dependency cycle in chain '${chain.id}' includes '$id'"
                return
            }
            byId[id]?.dependsOn.orEmpty().forEach(::visit)
            visiting.remove(id)
            visited += id
        }
        chain.steps.forEach { visit(it.id) }
        return errors
    }

    private fun pipelineOutput(chains: List<CompletedChain>): String = buildString {
        appendLine("Tool execution pipeline results:")
        chains.forEach { chain ->
            appendLine("- Chain ${chain.id}: ${if (chain.success) "success" else "failed"}")
            chain.errorMessage?.let { appendLine("  Error: $it") }
            chain.steps.forEach { step ->
                appendLine("  - Step ${step.id} (${step.serverName}/${step.toolName}): ${if (step.success) "success" else "failed"}")
                appendLine("    Input: ${step.argumentsJson}")
                step.errorMessage?.let { appendLine("    Error: $it") }
                appendLine("    Result: ${step.content}")
            }
        }
    }.trim()
}

object ToolExecutionPlanParser {
    fun parse(planJson: String): PlannedToolExecutionPlan {
        val root = McpJson.parse(planJson).asObject() ?: error("toolExecutionPlan must be a JSON object")
        val chains = root["chains"]?.asArray()?.map(::parseChain) ?: error("toolExecutionPlan.chains is required")
        val constraints = root["constraints"]?.asObject().orEmpty()
        val allowParallel = (constraints["allowParallel"] as? JsonValue.Bool)?.value ?: true
        return PlannedToolExecutionPlan(chains = chains, allowParallel = allowParallel).withImpliedDependencies()
    }

    private fun parseChain(value: JsonValue): PlannedToolChain {
        val root = value.asObject() ?: error("Each chain must be an object")
        return PlannedToolChain(
            id = root["id"]?.asString() ?: error("Chain id is required"),
            mode = when (root["mode"]?.asString()?.lowercase()) {
                "sequential" -> ToolChainExecutionMode.SEQUENTIAL
                "parallel" -> ToolChainExecutionMode.PARALLEL
                else -> error("Chain mode must be sequential or parallel")
            },
            dependsOn = stringArray(root["dependsOn"]),
            inputJson = McpJson.stringify(root["input"] ?: JsonValue.ObjectValue(emptyMap())),
            steps = root["steps"]?.asArray()?.map(::parseStep) ?: error("Chain steps are required")
        )
    }

    private fun parseStep(value: JsonValue): PlannedToolStep {
        val root = value.asObject() ?: error("Each step must be an object")
        return PlannedToolStep(
            id = root["id"]?.asString() ?: error("Step id is required"),
            serverName = root["server"]?.asString() ?: error("Step server is required"),
            toolName = root["tool"]?.asString() ?: error("Step tool is required"),
            argumentsJson = McpJson.stringify(root["arguments"] ?: JsonValue.ObjectValue(emptyMap())),
            dependsOn = stringArray(root["dependsOn"]),
            inputMappings = stringMap(root["inputMappings"])
        )
    }

    private fun stringArray(value: JsonValue?): List<String> =
        value?.asArray().orEmpty().mapNotNull { it.asString() }

    private fun stringMap(value: JsonValue?): Map<String, String> =
        value?.asObject().orEmpty().mapNotNull { (key, item) ->
            item.asString()?.let { key to it }
        }.toMap()

    private fun PlannedToolExecutionPlan.withImpliedDependencies(): PlannedToolExecutionPlan {
        val stepOwners = chains.flatMap { chain -> chain.steps.map { step -> step.id to chain.id } }.toMap()
        return copy(chains = chains.map { chain ->
            val chainInputReferences = mappingSourcesFromInput(chain.inputJson)
            val stepReferences = chain.steps.flatMap { step ->
                step.dependsOn + step.inputMappings.values.mapNotNull(::stepDependencyFromMapping)
            }
            val chainDependencies = chain.dependsOn +
                chainInputReferences.mapNotNull(::chainDependencyFromMapping) +
                chainInputReferences.mapNotNull(::stepDependencyFromMapping).mapNotNull { stepId ->
                    stepOwners[stepId]?.takeIf { it != chain.id }
                } +
                chain.steps.flatMap { it.inputMappings.values }.mapNotNull(::chainDependencyFromMapping) +
                stepReferences.mapNotNull { stepId -> stepOwners[stepId]?.takeIf { it != chain.id } }
            chain.copy(
                dependsOn = chainDependencies.distinct(),
                steps = chain.steps.map { step ->
                    val stepDependencies = (step.dependsOn + step.inputMappings.values.mapNotNull(::stepDependencyFromMapping))
                        .filter { stepOwners[it] == chain.id }
                        .filter { it != step.id }
                    step.copy(dependsOn = stepDependencies.distinct())
                }
            )
        })
    }

    private fun mappingSourcesFromInput(inputJson: String): List<String> =
        McpJson.parse(inputJson).asObject().orEmpty().values.mapNotNull { it.asString() }

    private fun stepDependencyFromMapping(source: String): String? =
        mappingTerms(source).firstNotNullOfOrNull { term ->
            normalizeMappingTerm(term).takeIf { it.startsWith("steps.") }
            ?.split(".")
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
        }

    private fun chainDependencyFromMapping(source: String): String? =
        mappingTerms(source).firstNotNullOfOrNull { term ->
            normalizeMappingTerm(term).takeIf { it.startsWith("chains.") }
            ?.split(".")
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
        }

    private fun mappingTerms(source: String): List<String> =
        source.split("+")
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { listOf(source.trim()) }

    private fun normalizeMappingTerm(term: String): String {
        val trimmed = term.trim()
        if (!trimmed.startsWith("JSON.stringify(") || !trimmed.endsWith(")")) return trimmed
        return trimmed.removePrefix("JSON.stringify(").dropLast(1).trim()
    }
}

private data class CompletedChain(
    val id: String,
    val success: Boolean,
    val steps: List<CompletedStep>,
    val errorMessage: String? = null
) {
    companion object {
        fun failed(id: String, message: String): CompletedChain =
            CompletedChain(id = id, success = false, steps = emptyList(), errorMessage = message)
    }
}

private data class CompletedStep(
    val id: String,
    val serverName: String,
    val toolName: String,
    val argumentsJson: String,
    val content: String,
    val success: Boolean,
    val errorMessage: String? = null
) {
    companion object {
        fun fromResult(id: String, argumentsJson: String, result: McpToolCallResult, success: Boolean): CompletedStep =
            CompletedStep(
                id = id,
                serverName = result.serverName,
                toolName = result.toolName,
                argumentsJson = argumentsJson,
                content = result.content,
                success = success,
                errorMessage = if (success) null else result.content
            )

        fun failed(
            id: String,
            serverName: String,
            toolName: String,
            message: String,
            argumentsJson: String = "{}"
        ): CompletedStep =
            CompletedStep(
                id = id,
                serverName = serverName,
                toolName = toolName,
                argumentsJson = argumentsJson,
                content = "",
                success = false,
                errorMessage = message
            )
    }
}
