package cli

import java.awt.Desktop
import java.nio.file.Path
import java.util.Locale

interface FileOpener {
    fun open(path: Path)
}

class SystemFileOpener : FileOpener {
    override fun open(path: Path) {
        val file = path.toFile()
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(file)
                return
            }
        }

        val command = fallbackCommand(path)
            ?: throw IllegalStateException("No supported file opener is available on this operating system.")
        ProcessBuilder(command).start()
    }

    private fun fallbackCommand(path: Path): List<String>? {
        val os = System.getProperty("os.name").lowercase(Locale.US)
        val value = path.toAbsolutePath().normalize().toString()
        return when {
            os.contains("windows") -> listOf("rundll32", "url.dll,FileProtocolHandler", value)
            os.contains("mac") || os.contains("darwin") -> listOf("open", value)
            os.contains("linux") || os.contains("unix") || os.contains("bsd") -> listOf("xdg-open", value)
            else -> null
        }
    }
}
