package agent

object ModelRequestJson {
    fun contextWindowOptions(settings: AgentSettings): String? =
        settings.modelContextWindowTokens
            .takeIf { it > 0L }
            ?.let { """"options": {"num_ctx": $it}""" }
}
