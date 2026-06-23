package mcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class McpServerConfigResolverTest {
    @Test
    fun `http url resolves to streamable http config`() {
        val config = McpServerConfigResolver.resolve("remote", listOf("http://127.0.0.1:8080/mcp"))

        assertEquals(
            McpServerConfig(
                name = "remote",
                command = emptyList(),
                transport = McpTransport.HTTP,
                url = "http://127.0.0.1:8080/mcp"
            ),
            config
        )
    }

    @Test
    fun `project directory can resolve mcp client config`() {
        val directory = Files.createTempDirectory("aichat-mcp-project-config")
        Files.createDirectories(directory.resolve("examples"))
        Files.writeString(
            directory.resolve("examples").resolve("mcp-client-config.json"),
            """
            {
              "mcpServers": {
                "amiibo": {
                  "command": "java",
                  "args": ["-jar", "build\\libs\\amiibo-mcp-0.1.0.jar"],
                  "cwd": "${directory.toString().replace("\\", "\\\\")}",
                  "env": {
                    "AMIIBO_TRANSPORT": "stdio",
                    "AMIIBO_MAX_RESULTS": "50"
                  }
                }
              }
            }
            """.trimIndent(),
            StandardCharsets.UTF_8
        )

        val config = McpServerConfigResolver.resolve("amiibo", listOf(directory.toString()))

        assertEquals(
            McpServerConfig(
                name = "amiibo",
                command = listOf("java", "-jar", "build\\libs\\amiibo-mcp-0.1.0.jar"),
                workingDirectory = directory.toString(),
                environment = mapOf(
                    "AMIIBO_TRANSPORT" to "stdio",
                    "AMIIBO_MAX_RESULTS" to "50"
                )
            ),
            config
        )
    }

    @Test
    fun `project directory falls back to built jar`() {
        val directory = Files.createTempDirectory("aichat-mcp-project-jar")
        val libs = directory.resolve("build").resolve("libs")
        Files.createDirectories(libs)
        val jar = libs.resolve("server.jar")
        Files.writeString(jar, "", StandardCharsets.UTF_8)

        val config = McpServerConfigResolver.resolve("server", listOf(directory.toString()))

        assertEquals(
            McpServerConfig(
                name = "server",
                command = listOf("java", "-jar", jar.toAbsolutePath().normalize().toString()),
                workingDirectory = directory.toString()
            ),
            config
        )
    }
}
