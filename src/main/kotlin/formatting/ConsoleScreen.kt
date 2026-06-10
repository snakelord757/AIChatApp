package formatting

object ConsoleScreen {
    fun clear() {
        if (Ansi.isEnabled) {
            print("\u001B[2J\u001B[H")
            System.out.flush()
            return
        }

        if (isWindows() && clearWindowsConsole()) {
            return
        }

        repeat(60) { println() }
        System.out.flush()
    }

    private fun clearWindowsConsole(): Boolean {
        return runCatching {
            ProcessBuilder("cmd.exe", "/c", "cls")
                .inheritIO()
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
