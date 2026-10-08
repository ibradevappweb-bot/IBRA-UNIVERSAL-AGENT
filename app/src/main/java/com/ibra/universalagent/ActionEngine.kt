package com.ibra.universalagent

data class ActionPlan(val request: String, val actions: List<String>)
data class ActionExecution(
    val plan: ActionPlan,
    val observedResult: String,
    val completedSteps: List<PlanStep> = emptyList(),
    val failedStep: PlanStep? = null
)

class ActionEngine {
    fun plan(agentPlan: AgentPlan): ActionPlan =
        ActionPlan(
            request = agentPlan.request,
            actions = agentPlan.steps.map { it.action.name }
        )

    fun execute(agentPlan: AgentPlan): ActionExecution {
        val completed = mutableListOf<PlanStep>()

        for (step in agentPlan.steps) {
            when (step.action) {
                PlanAction.PREPARE,
                PlanAction.RESPOND -> completed += step

                // Real Android integrations will be attached here one by one.
                PlanAction.OPEN_APP,
                PlanAction.SEARCH,
                PlanAction.NAVIGATE,
                PlanAction.SEND_MESSAGE -> return ActionExecution(
                    plan = plan(agentPlan),
                    observedResult = "Action non connectée à Android : " + step.action,
                    completedSteps = completed,
                    failedStep = step
                )
            }
        }

        return ActionExecution(
            plan = plan(agentPlan),
            observedResult = "Plan exécuté par le moteur : " + agentPlan.request,
            completedSteps = completed
        )
    }
}
