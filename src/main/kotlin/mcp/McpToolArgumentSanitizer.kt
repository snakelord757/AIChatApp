package mcp

object McpToolArgumentSanitizer {
    fun sanitize(argumentsJson: String, tool: McpTool?): String {
        if (tool == null) return argumentsJson
        val schema = tool.inputSchema?.takeIf { it.isNotBlank() } ?: return argumentsJson
        val schemaObject = runCatching { McpJson.parse(schema).asObject() }.getOrNull() ?: return argumentsJson
        if (schemaObject["type"]?.asString() != "object") return argumentsJson
        val parsedArguments = runCatching { McpJson.parse(argumentsJson) }.getOrNull()
            ?: invalid(tool, "arguments must be valid JSON")
        val arguments = parsedArguments.asObject()
            ?: invalid(tool, "arguments must be a JSON object")
        if ((schemaObject["additionalProperties"] as? JsonValue.Bool)?.value == true) {
            validateRequired(arguments, schemaObject, tool)
            validateProperties(arguments, schemaObject["properties"]?.asObject().orEmpty(), tool)
            return argumentsJson
        }
        val properties = schemaObject["properties"]?.asObject().orEmpty()
        val filtered = arguments.filterKeys { it in properties.keys }
        validateRequired(filtered, schemaObject, tool)
        validateProperties(filtered, properties, tool)
        return McpJson.stringify(JsonValue.ObjectValue(filtered))
    }

    private fun validateRequired(
        arguments: Map<String, JsonValue>,
        schema: Map<String, JsonValue>,
        tool: McpTool
    ) {
        schema["required"]?.asArray().orEmpty()
            .mapNotNull { it.asString() }
            .forEach { name ->
                val value = arguments[name]
                if (value == null || value == JsonValue.Null) {
                    invalid(tool, "required argument '$name' is missing")
                }
            }
    }

    private fun validateProperties(
        arguments: Map<String, JsonValue>,
        properties: Map<String, JsonValue>,
        tool: McpTool
    ) {
        arguments.forEach { (name, value) ->
            val schema = properties[name]?.asObject() ?: return@forEach
            validateType(name, value, schema, tool)
            validateString(name, value, schema, tool)
            validateNumber(name, value, schema, tool)
        }
    }

    private fun validateType(
        name: String,
        value: JsonValue,
        schema: Map<String, JsonValue>,
        tool: McpTool
    ) {
        when (schema["type"]?.asString()) {
            "string" -> if (value !is JsonValue.StringValue) invalid(tool, "argument '$name' must be a string")
            "boolean" -> if (value !is JsonValue.Bool) invalid(tool, "argument '$name' must be a boolean")
            "integer" -> if ((value as? JsonValue.Number)?.raw?.toLongOrNull() == null) invalid(tool, "argument '$name' must be an integer")
            "number" -> if ((value as? JsonValue.Number)?.raw?.toDoubleOrNull() == null) invalid(tool, "argument '$name' must be a number")
            "object" -> if (value !is JsonValue.ObjectValue) invalid(tool, "argument '$name' must be an object")
            "array" -> if (value !is JsonValue.ArrayValue) invalid(tool, "argument '$name' must be an array")
        }
    }

    private fun validateString(
        name: String,
        value: JsonValue,
        schema: Map<String, JsonValue>,
        tool: McpTool
    ) {
        val text = value.asString() ?: return
        schema["minLength"]?.numberLong()?.let { min ->
            if (text.length < min) invalid(tool, "argument '$name' must be at least $min characters")
        }
        schema["maxLength"]?.numberLong()?.let { max ->
            if (text.length > max) invalid(tool, "argument '$name' must be at most $max characters")
        }
        schema["pattern"]?.asString()?.takeIf { it.isNotBlank() }?.let { pattern ->
            if (!Regex(pattern).matches(text)) invalid(tool, "argument '$name' must match pattern $pattern")
        }
    }

    private fun validateNumber(
        name: String,
        value: JsonValue,
        schema: Map<String, JsonValue>,
        tool: McpTool
    ) {
        val number = (value as? JsonValue.Number)?.raw?.toDoubleOrNull() ?: return
        schema["minimum"]?.numberDouble()?.let { min ->
            if (number < min) invalid(tool, "argument '$name' must be at least ${min.cleanNumber()}")
        }
        schema["maximum"]?.numberDouble()?.let { max ->
            if (number > max) invalid(tool, "argument '$name' must be at most ${max.cleanNumber()}")
        }
    }

    private fun invalid(tool: McpTool, reason: String): Nothing =
        throw IllegalArgumentException("MCP tool ${tool.serverName}/${tool.name} rejected arguments before execution: $reason.")

    private fun JsonValue.numberLong(): Long? = (this as? JsonValue.Number)?.raw?.toLongOrNull()

    private fun JsonValue.numberDouble(): Double? = (this as? JsonValue.Number)?.raw?.toDoubleOrNull()

    private fun Double.cleanNumber(): String =
        if (this % 1.0 == 0.0) toLong().toString() else toString()
}
