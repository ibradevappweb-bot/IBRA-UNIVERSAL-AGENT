package com.ibra.universalagent

enum class AgentState {
    IDLE,
    UNDERSTANDING,
    PLANNING,
    WAITING_CONFIRMATION,
    EXECUTING,
    OBSERVING,
    VERIFYING,
    CORRECTING,
    COMPLETED,
    FAILED,
    BLOCKED
}

data class AgentStateSnapshot(
    val state: AgentState,
    val request: String,
    val taskType: TaskType? = null,
    val attempt: Int = 0,
    val message: String = ""
)

class AgentStateEngine {
    private var snapshot = AgentStateSnapshot(AgentState.IDLE, "")

    fun current(): AgentStateSnapshot = snapshot

    fun start(request: String) {
        snapshot = AgentStateSnapshot(AgentState.UNDERSTANDING, request)
    }

    fun set(
        state: AgentState,
        taskType: TaskType? = snapshot.taskType,
        attempt: Int = snapshot.attempt,
        message: String = snapshot.message
    ) {
        snapshot = snapshot.copy(
            state = state,
            taskType = taskType,
            attempt = attempt,
            message = message
        )
    }

    fun reset() {
        snapshot = AgentStateSnapshot(AgentState.IDLE, "")
    }
}
