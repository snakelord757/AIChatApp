package chat

enum class Role(val apiName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant")
}
