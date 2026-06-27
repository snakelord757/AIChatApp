package task

import mcp.McpTool
import mcp.McpToolCallResult
import mcp.McpToolGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolExecutionPipelineTest {
    @Test
    fun `input mapping concatenates step output fields before dependent tool call`() {
        val gateway = RecordingGateway()
        val pipeline = ToolExecutionPipeline(gateway)

        val result = pipeline.run(
            """
            {
              "chains": [
                {
                  "id": "random_then_games",
                  "mode": "sequential",
                  "dependsOn": [],
                  "input": {},
                  "steps": [
                    {
                      "id": "get_random",
                      "server": "amiibo_api",
                      "tool": "random_amiibo",
                      "arguments": {},
                      "dependsOn": [],
                      "inputMappings": {}
                    },
                    {
                      "id": "get_games",
                      "server": "amiibo_api",
                      "tool": "game_info",
                      "arguments": {},
                      "dependsOn": ["get_random"],
                      "inputMappings": {
                        "amiiboId": "steps.get_random.output.head + steps.get_random.output.tail"
                      }
                    }
                  ]
                }
              ],
              "constraints": {"allowParallel": false}
            }
            """.trimIndent()
        )

        assertTrue(result.success)
        assertEquals("""{"amiiboId":"350b0000042d1802"}""", gateway.calls.getValue("game_info"))
    }

    private class RecordingGateway : McpToolGateway {
        val calls = linkedMapOf<String, String>()

        override fun availableTools(): List<McpTool> =
            listOf(
                McpTool(
                    serverName = "amiibo_api",
                    name = "random_amiibo",
                    inputSchema = """{"type":"object","properties":{}}"""
                ),
                McpTool(
                    serverName = "amiibo_api",
                    name = "game_info",
                    inputSchema = """{"type":"object","required":["amiiboId"],"properties":{"amiiboId":{"type":"string","minLength":16,"maxLength":16,"pattern":"^[0-9a-fA-F]{16}$"}}}"""
                )
            )

        override fun callTool(serverName: String, toolName: String, argumentsJson: String): McpToolCallResult {
            calls[toolName] = argumentsJson
            val content = when (toolName) {
                "random_amiibo" -> """{"head":"350b0000","tail":"042d1802","name":"Malzeno"}"""
                "game_info" -> """{"games":["Monster Hunter Rise"]}"""
                else -> error("Unexpected tool: $toolName")
            }
            return McpToolCallResult(serverName, toolName, content)
        }
    }
}
