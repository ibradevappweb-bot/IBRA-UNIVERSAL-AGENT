package com.ibra.universalagent

enum class PlanAction {
    PREPARE,
    OPEN_APP,
    SEARCH,
    NAVIGATE,
    SEND_MESSAGE,
    RESPOND
}

data class PlanStep(
    val index: Int,
    val action: PlanAction,
    val target: String?,
    val parameters: Map<String, String>
)

data class AgentPlan(
    val request: String,
    val taskType: TaskType,
    val steps: List<PlanStep>,
    val requiresConfirmation: Boolean
)

class PlannerEngine {
    fun createPlan(decision: BrainDecision): AgentPlan {
        val steps = when (decision.taskType) {
            TaskType.CHAT -> listOf(
                PlanStep(1, PlanAction.RESPOND, null, decision.parameters)
            )
            TaskType.INFORMATION -> listOf(
                PlanStep(1, PlanAction.PREPARE, decision.target, decision.parameters),
                PlanStep(2, PlanAction.RESPOND, null, decision.parameters)
            )
            TaskType.SEARCH -> listOf(
                PlanStep(1, PlanAction.PREPARE, decision.target, decision.parameters),
                PlanStep(2, PlanAction.SEARCH, decision.target, decision.parameters),
                PlanStep(3, PlanAction.RESPOND, null, decision.parameters)
            )
            TaskType.OPEN_APP -> listOf(
                PlanStep(1, PlanAction.PREPARE, decision.target, decision.parameters),
                PlanStep(2, PlanAction.OPEN_APP, decision.target, decision.parameters),
                PlanStep(3, PlanAction.RESPOND, null, decision.parameters)
            )
            TaskType.NAVIGATION -> listOf(
                PlanStep(1, PlanAction.PREPARE, decision.target, decision.parameters),
                PlanStep(2, PlanAction.NAVIGATE, decision.target, decision.parameters),
                PlanStep(3, PlanAction.RESPOND, null, decision.parameters)
            )
            TaskType.MESSAGE -> listOf(
                PlanStep(1, PlanAction.PREPARE, decision.target, decision.parameters),
                PlanStep(2, PlanAction.SEND_MESSAGE, decision.target, decision.parameters),
                PlanStep(3, PlanAction.RESPOND, null, decision.parameters)
            )
            TaskType.OTHER -> listOf(
                PlanStep(1, PlanAction.PREPARE, decision.target, decision.parameters),
                PlanStep(2, PlanAction.RESPOND, null, decision.parameters)
            )
        }

        return AgentPlan(
            request = decision.normalizedRequest,
            taskType = decision.taskType,
            steps = steps,
            requiresConfirmation = decision.requiresConfirmation
        )
    }
}
