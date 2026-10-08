package com.ibra.universalagent

data class AgentContext(
    val request: String,
    val taskType: TaskType? = null,
    val target: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val facts: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val memory: List<MemoryEntry> = emptyList(),
    val attempt: Int = 0
) {
    fun withFact(key: String, value: String): AgentContext =
        copy(facts = facts + (key to value))

    fun withMemory(entry: MemoryEntry): AgentContext =
        copy(memory = memory + entry)

    fun withAttempt(value: Int): AgentContext =
        copy(attempt = value.coerceAtLeast(0))
}
