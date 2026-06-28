package task

import mcp.McpTool
import mcp.McpToolCallResult
import mcp.McpToolGateway
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `chain input and nested completed chain step output are resolved before dependent calls`() {
        val gateway = RecordingGateway()
        val pipeline = ToolExecutionPipeline(gateway)

        val result = pipeline.run(
            """
            {
              "chains": [
                {
                  "id": "random_chain",
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
                    }
                  ]
                },
                {
                  "id": "games_chain",
                  "mode": "sequential",
                  "dependsOn": ["random_chain"],
                  "input": {
                    "amiiboId": "steps.get_random.output.head + steps.get_random.output.tail"
                  },
                  "steps": [
                    {
                      "id": "get_games",
                      "server": "amiibo_api",
                      "tool": "game_info",
                      "arguments": {},
                      "dependsOn": [],
                      "inputMappings": {
                        "amiiboId": "chain.input.amiiboId"
                      }
                    }
                  ]
                },
                {
                  "id": "descriptions_chain",
                  "mode": "sequential",
                  "dependsOn": ["games_chain"],
                  "input": {},
                  "steps": [
                    {
                      "id": "describe_games",
                      "server": "rawg",
                      "tool": "ask_pipeworx",
                      "arguments": {},
                      "dependsOn": [],
                      "inputMappings": {
                        "question": "'Describe these games: ' + chains.games_chain.steps.get_games.output"
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
        assertEquals("""{"question":"Describe these games: {\"games\":[\"Monster Hunter Rise\"]}"}""", gateway.calls.getValue("ask_pipeworx"))
    }

    @Test
    fun `pipeline output keeps full tool result for execution context`() {
        val gateway = RecordingGateway(longDescription = true)
        val pipeline = ToolExecutionPipeline(gateway)

        val result = pipeline.run(
            """
            {
              "chains": [
                {
                  "id": "describe",
                  "mode": "sequential",
                  "dependsOn": [],
                  "input": {},
                  "steps": [
                    {
                      "id": "describe_games",
                      "server": "rawg",
                      "tool": "ask_pipeworx",
                      "arguments": {"question": "Describe these games"},
                      "dependsOn": [],
                      "inputMappings": {}
                    }
                  ]
                }
              ],
              "constraints": {"allowParallel": false}
            }
            """.trimIndent()
        )

        assertTrue(result.success)
        assertContains(result.output, "END_OF_LONG_TOOL_RESULT")
    }

    @Test
    fun `blank resolved input mapping fails before calling tool`() {
        val gateway = RecordingGateway()
        val pipeline = ToolExecutionPipeline(gateway)

        val result = pipeline.run(
            """
            {
              "chains": [
                {
                  "id": "broken",
                  "mode": "sequential",
                  "dependsOn": [],
                  "input": {},
                  "steps": [
                    {
                      "id": "search",
                      "server": "rawg",
                      "tool": "search_games",
                      "arguments": {},
                      "dependsOn": [],
                      "inputMappings": {
                        "query": "steps.missing.output.name"
                      }
                    }
                  ]
                }
              ],
              "constraints": {"allowParallel": false}
            }
            """.trimIndent()
        )

        assertFalse(result.success)
        assertContains(result.output, "Input mapping for 'query' resolved to blank")
        assertFalse(gateway.calls.containsKey("search_games"))
    }

    @Test
    fun `current amiibo plan aliases amiiboId games and json stringify wrapper`() {
        val gateway = RecordingGateway(gameInfoWithPlatformBuckets = true)
        val pipeline = ToolExecutionPipeline(gateway)

        val result = pipeline.run(
            """
            {
              "chains": [
                {
                  "id": "get_random_amiibo",
                  "mode": "sequential",
                  "dependsOn": [],
                  "input": {},
                  "steps": [
                    {
                      "id": "random_amiibo_step",
                      "server": "amiibo_api",
                      "tool": "random_amiibo",
                      "arguments": {},
                      "dependsOn": [],
                      "inputMappings": {}
                    }
                  ]
                },
                {
                  "id": "get_game_info",
                  "mode": "sequential",
                  "dependsOn": ["get_random_amiibo"],
                  "input": {},
                  "steps": [
                    {
                      "id": "game_info_step",
                      "server": "amiibo_api",
                      "tool": "game_info",
                      "arguments": {"amiiboId": ""},
                      "dependsOn": [],
                      "inputMappings": {
                        "amiiboId": "chains.get_random_amiibo.steps.random_amiibo_step.output.amiiboId"
                      }
                    }
                  ]
                },
                {
                  "id": "describe_games",
                  "mode": "sequential",
                  "dependsOn": ["get_game_info"],
                  "input": {},
                  "steps": [
                    {
                      "id": "describe_all_games",
                      "server": "rawg",
                      "tool": "ask_pipeworx",
                      "arguments": {"question": ""},
                      "dependsOn": [],
                      "inputMappings": {
                        "question": "'Games list: ' + JSON.stringify(chains.get_game_info.steps.game_info_step.output.games)"
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
        assertContains(gateway.calls.getValue("ask_pipeworx"), "Monster Hunter Rise")
        assertContains(gateway.calls.getValue("ask_pipeworx"), "Splatoon 3")
    }

    private class RecordingGateway(
        private val longDescription: Boolean = false,
        private val gameInfoWithPlatformBuckets: Boolean = false
    ) : McpToolGateway {
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
                ),
                McpTool(
                    serverName = "rawg",
                    name = "ask_pipeworx",
                    inputSchema = """{"type":"object","required":["question"],"properties":{"question":{"type":"string"}}}"""
                ),
                McpTool(
                    serverName = "rawg",
                    name = "search_games",
                    inputSchema = """{"type":"object","properties":{"query":{"type":"string"}}}"""
                )
            )

        override fun callTool(serverName: String, toolName: String, argumentsJson: String): McpToolCallResult {
            calls[toolName] = argumentsJson
            val content = when (toolName) {
                "random_amiibo" -> """{"head":"350b0000","tail":"042d1802","name":"Malzeno"}"""
                "game_info" -> if (gameInfoWithPlatformBuckets) {
                    """{"games3DS":[{"gameName":"Monster Hunter Rise"}],"gamesSwitch":[{"gameName":"Splatoon 3"}],"gamesWiiU":[]}"""
                } else {
                    """{"games":["Monster Hunter Rise"]}"""
                }
                "ask_pipeworx" -> if (longDescription) {
                    """{"answer":"${"A".repeat(700)}END_OF_LONG_TOOL_RESULT"}"""
                } else {
                    """{"answer":"Monster Hunter Rise is an action RPG."}"""
                }
                "search_games" -> """{"games":[]}"""
                else -> error("Unexpected tool: $toolName")
            }
            return McpToolCallResult(serverName, toolName, content)
        }
    }
}
