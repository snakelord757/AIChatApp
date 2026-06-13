package chat

enum class ContextStrategy(val displayName: String) {
    SLIDING_WINDOW("Sliding Window"),
    STICKY_FACTS("Sticky Facts"),
    BRANCHING("Branching");

    companion object {
        fun parse(value: String): ContextStrategy? {
            return when (value.trim().lowercase()) {
                "sliding", "sliding_window", "sliding-window", "window" -> SLIDING_WINDOW
                "facts", "sticky", "sticky_facts", "sticky-facts" -> STICKY_FACTS
                "branching", "branch" -> BRANCHING
                else -> null
            }
        }
    }
}
