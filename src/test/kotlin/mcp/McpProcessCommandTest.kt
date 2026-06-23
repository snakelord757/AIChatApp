package mcp

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class McpProcessCommandTest {
    @Test
    fun `java executable resolves from current JVM home`() {
        val command = McpProcessCommand.resolve(listOf("java", "-jar", "server.jar"))

        assertEquals(
            Paths.get(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(if (System.getProperty("os.name").contains("windows", ignoreCase = true)) "java.exe" else "java")
                .toAbsolutePath()
                .normalize()
                .toString(),
            command.first()
        )
        assertEquals(listOf("-jar", "server.jar"), command.drop(1))
    }

    @Test
    fun `java executable falls back to JAVA_HOME when java home has no executable`() {
        val base = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val javaHome = base.resolve("graal-native-runtime")
        val envJavaHome = base.resolve("jdk-20")

        val resolved = McpProcessCommand.javaExecutable(
            javaHomeProperty = javaHome.toString(),
            javaHomeEnvironment = envJavaHome.toString(),
            osName = "Windows 11",
            exists = { it.startsWith(envJavaHome) }
        )

        assertEquals(
            envJavaHome.resolve("bin").resolve("java.exe").toAbsolutePath().normalize().toString(),
            resolved?.toString()
        )
    }

    @Test
    fun `non java executable is unchanged`() {
        assertEquals(
            listOf("npx.cmd", "-y", "server"),
            McpProcessCommand.resolve(listOf("npx.cmd", "-y", "server"))
        )
    }
}
