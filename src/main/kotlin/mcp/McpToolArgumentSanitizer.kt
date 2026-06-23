package mcp

object McpToolArgumentSanitizer {
    fun sanitize(argumentsJson: String, tool: McpTool?): String {
        if (tool == null) return argumentsJson
        val schema = tool.inputSchema?.takeIf { it.isNotBlank() } ?: return argumentsJson
        val arguments = runCatching { McpJson.parse(argumentsJson).asObject() }.getOrNull() ?: return argumentsJson
        val schemaObject = runCatching { McpJson.parse(schema).asObject() }.getOrNull() ?: return argumentsJson
        if ((schemaObject["additionalProperties"] as? JsonValue.Bool)?.value == true) return argumentsJson
        if (schemaObject["type"]?.asString() != "object") return argumentsJson
        val properties = schemaObject["properties"]?.asObject().orEmpty()
        val filtered = arguments.filterKeys { it in properties.keys }
        return McpJson.stringify(JsonValue.ObjectValue(filtered))
    }
}
