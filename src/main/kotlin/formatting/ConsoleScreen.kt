package formatting

object ConsoleScreen {
    fun clear() {
        if (Ansi.isEnabled) {
            print("\u001B[2J\u001B[H")
            System.out.flush()
        }
    }
}
