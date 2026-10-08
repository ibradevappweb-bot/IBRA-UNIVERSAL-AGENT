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
    val requiresConfirmation: Boolean,
    val contextKeys: List<String> = emptyList()
)

class PlannerEngine {
    fun createPlan(decision: BrainDecision, context: AgentContext): AgentPlan {
        val target = context.target ?: decision.target
        val parameters = decision.parameters + context.facts
        val steps = when (decision.taskType) {
            TaskType.CHAT -> listOf(PlanStep(1, PlanAction.RESPOND, null, parameters))
            TaskType.INFORMATION -> listOf(
                PlanStep(1, PlanAction.PREPARE, target, parameters),
                PlanStep(2, PlanAction.RESPOND, null, parameters)
            )
            TaskType.SEARCH -> listOf(
                PlanStep(1, PlanAction.PREPARE, target, parameters),
                PlanStep(2, PlanAction.SEARCH, target, parameters),
                PlanStep(3, PlanAction.RESPOND, null, parameters)
            )
            TaskType.OPEN_APP -> listOf(
                PlanStep(1, PlanAction.PREPARE, target, parameters),
                PlanStep(2, PlanAction.OPEN_APP, target, parameters),
                PlanStep(3, PlanAction.RESPOND, null, parameters)
            )
            TaskType.NAVIGATION -> listOf(
                PlanStep(1, PlanAction.PREPARE, target, parameters),
                PlanStep(2, PlanAction.NAVIGATE, target, parameters),
                PlanStep(3, PlanAction.RESPOND, null, parameters)
            )
            TaskType.MESSAGE -> listOf(
                PlanStep(1, PlanAction.PREPARE, target, parameters),
                PlanStep(2, PlanAction.SEND_MESSAGE, target, parameters),
                PlanStep(3, PlanAction.RESPOND, null, parameters)
            )
            TaskType.OTHER -> listOf(
                PlanStep(1, PlanAction.PREPARE, target, parameters),
                PlanStep(2, PlanAction.RESPOND, null, parameters)
            )
        }

        return AgentPlan(
            request = decision.normalizedRequest,
            taskType = decision.taskType,
            steps = steps,
            requiresConfirmation = decision.requiresConfirmation,
            contextKeys = (context.facts.keys + context.memory.map { it.key }).distinct()
        )
    }

    fun createPlan(decision: BrainDecision): AgentPlan =
        createPlan(
            decision,
            AgentContext(
                request = decision.normalizedRequest,
                taskType = decision.taskType,
                target = decision.target,
                parameters = decision.parameters
            )
        )
}
