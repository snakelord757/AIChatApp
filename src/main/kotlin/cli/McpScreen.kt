package cli

import mcp.McpClient
import mcp.McpConnectionState
import mcp.McpServerConfig
import mcp.McpServerConfigResolver
import mcp.McpServerStore

class McpScreen(
    private val renderer: ConsoleRenderer,
    private val store: McpServerStore,
    private val client: McpClient,
    private val input: ConsoleInput = ConsoleInput()
) {
    fun open() {
        var configs = store.load()
        client.configure(configs)
        renderHelp()

        while (true) {
            print("mcp> ")
            val command = input.readLine()?.trimMcpInput() ?: return
            if (command.isBlank()) {
                renderer.renderSystem("Enter an MCP command.")
                continue
            }

            val parts = command.split(Regex("\\s+"))
            when (parts.first().lowercase()) {
                "help" -> renderHelp()
                "list" -> renderer.renderMcpServers(client.listServers())
                "connect" -> {
                    val config = parseConnect(command)
                    if (config == null) {
                        renderer.renderError("Usage: connect <name> <command|httpUrl|projectDirectory> [args...]")
                    } else {
                        val status = client.connect(config)
                        if (status.state == McpConnectionState.CONNECTED) {
                            configs = configs.filterNot { it.name == config.name } + config
                            store.save(configs)
                        }
                        renderer.renderMcpServers(listOf(status))
                    }
                }
                "tools" -> handleTools(parts)
                "call" -> handleCall(command)
                "remove", "delete" -> {
                    val name = parts.getOrNull(1)
                    if (parts.size != 2 || name.isNullOrBlank()) {
                        renderer.renderError("Usage: remove <serverName>")
                    } else {
                        val existing = configs.firstOrNull { it.name == name }
                        if (existing == null) {
                            renderer.renderError("MCP server not found: $name")
                        } else {
                            client.disconnect(name)
                            configs = configs.filterNot { it.name == name }
                            store.save(configs)
                            renderer.renderSystem("MCP server removed: $name")
                        }
                    }
                }
                "clear" -> {
                    client.clear()
                    configs = emptyList()
                    store.save(configs)
                    renderer.renderSystem("All MCP servers removed.")
                }
                "back", "exit" -> {
                    renderer.renderSystem("Returning to chat.")
                    return
                }
                else -> renderer.renderError("Unknown MCP command. Enter help.")
            }
        }
    }

    private fun handleTools(parts: List<String>) {
        if (parts.size != 2) {
            renderer.renderError("Usage: tools <serverName|all>")
            return
        }
        try {
            val tools = if (parts[1].equals("all", ignoreCase = true)) {
                client.listServers().flatMap { status ->
                    runCatching { client.listTools(status.name) }.getOrElse { emptyList() }
                }
            } else {
                client.listTools(parts[1])
            }
            renderer.renderMcpTools(tools, includeServerName = parts[1].equals("all", ignoreCase = true))
        } catch (exception: RuntimeException) {
            renderer.renderError(exception.message ?: "Could not list MCP tools.")
        }
    }

    private fun handleCall(command: String) {
        val parts = command.split(Regex("\\s+"), limit = 4)
        if (parts.size != 4) {
            renderer.renderError("Usage: call <serverName> <toolName> <jsonArguments>")
            return
        }
        try {
            renderer.renderMcpToolResult(client.callTool(parts[1], parts[2], parts[3]))
        } catch (exception: RuntimeException) {
            renderer.renderError(exception.message ?: "Could not call MCP tool.")
        }
    }

    private fun parseConnect(command: String): McpServerConfig? {
        val parts = command.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val name = parts[1].trim()
        val executableAndArgs = parts.drop(2).filter { it.isNotBlank() }
        if (name.isBlank() || executableAndArgs.isEmpty()) return null
        return McpServerConfigResolver.resolve(name, executableAndArgs)
    }

    private fun renderHelp() {
        println("MCP commands:")
        println("help - show MCP help")
        println("list - list configured and connected MCP servers")
        println("connect <name> <command> [args...] - connect a stdio MCP server")
        println("connect <name> <httpUrl> - connect a Streamable HTTP MCP server")
        println("connect <name> <projectDirectory> - connect from an MCP project config")
        println("remove <serverName> - remove one MCP server")
        println("clear - remove all MCP servers")
        println("tools <serverName> - list tools for one MCP server")
        println("tools all - list tools from all MCP servers")
        println("call <serverName> <toolName> <jsonArguments> - call one MCP tool")
        println("back - return to chat")
    }

    private fun String.trimMcpInput(): String = trim { it.isWhitespace() || it == '\uFEFF' }
}
