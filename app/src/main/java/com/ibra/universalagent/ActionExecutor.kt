package com.ibra.universalagent

interface ActionExecutor {
    fun execute(step: PlanStep): ActionExecutorResult
}

data class ActionExecutorResult(
    val success: Boolean,
    val message: String
)

class SimulatedActionExecutor : ActionExecutor {
    override fun execute(step: PlanStep): ActionExecutorResult =
        ActionExecutorResult(
            success = false,
            message = "Action non connectée à Android : " + step.action
        )
}
