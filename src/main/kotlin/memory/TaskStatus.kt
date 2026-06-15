package memory

enum class TaskStatus {
    PENDING,
    DONE;

    companion object {
        fun parse(value: String?): TaskStatus? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
