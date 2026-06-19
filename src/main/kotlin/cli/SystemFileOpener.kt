package cli

import java.io.File
import java.nio.file.Path
import java.util.Locale

interface FileOpener {
    fun open(path: Path)
}

class SystemFileOpener : FileOpener {
    override fun open(path: Path) {
        if (tryOpenWithDesktop(path.toFile())) return

        val command = fallbackCommand(path)
            ?: throw IllegalStateException("No supported file opener is available on this operating system.")
        ProcessBuilder(command).start()
    }

    private fun tryOpenWithDesktop(file: File): Boolean {
        return try {
            val desktopClass = Class.forName("java.awt.Desktop")
            val actionClass = Class.forName("java.awt.Desktop\$Action")
            val isDesktopSupported = desktopClass.getMethod("isDesktopSupported").invoke(null) as? Boolean ?: false
            if (!isDesktopSupported) return false

            val desktop = desktopClass.getMethod("getDesktop").invoke(null)
            val openAction = java.lang.Enum.valueOf(actionClass.asSubclass(Enum::class.java), "OPEN")
            val isSupported = desktopClass.getMethod("isSupported", actionClass).invoke(desktop, openAction) as? Boolean ?: false
            if (!isSupported) return false

            desktopClass.getMethod("open", File::class.java).invoke(desktop, file)
            true
        } catch (_: Throwable) {
            false
        }
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
