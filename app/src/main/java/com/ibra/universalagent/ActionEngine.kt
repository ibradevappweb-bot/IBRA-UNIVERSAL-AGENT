package com.ibra.universalagent

data class ActionPlan(val request: String, val actions: List<String>)
data class ActionExecution(
    val plan: ActionPlan,
    val observedResult: String,
    val completedSteps: List<PlanStep> = emptyList(),
    val failedStep: PlanStep? = null,
    val contextAttempt: Int = 0,
    val contextFacts: Map<String, String> = emptyMap(),
    val contextMemoryKeys: List<String> = emptyList()
)

class ActionEngine(
    private val executor: ActionExecutor = SimulatedActionExecutor()
) {
    fun plan(agentPlan: AgentPlan): ActionPlan =
        ActionPlan(agentPlan.request, agentPlan.steps.map { it.action.name })

    fun execute(agentPlan: AgentPlan, context: AgentContext): ActionExecution {
        val completed = mutableListOf<PlanStep>()
        for (step in agentPlan.steps) {
            when (step.action) {
                PlanAction.PREPARE, PlanAction.RESPOND -> completed += step
                PlanAction.OPEN_APP, PlanAction.SEARCH, PlanAction.NAVIGATE, PlanAction.SEND_MESSAGE -> {
                    val result = executor.execute(step)
                    if (!result.success) return ActionExecution(
                        plan(agentPlan), result.message, completed, step,
                        context.attempt, context.facts, context.memory.map { it.key }
                    )
                    completed += step
                    return ActionExecution(
                        plan(agentPlan), result.message, completed,
                        contextAttempt = context.attempt,
                        contextFacts = context.facts,
                        contextMemoryKeys = context.memory.map { it.key }
                    )
                }
            }
        }
        return ActionExecution(
            plan(agentPlan),
            "Plan exécuté par le moteur : " + agentPlan.request,
            completed,
            contextAttempt = context.attempt,
            contextFacts = context.facts,
            contextMemoryKeys = context.memory.map { it.key }
        )
    }

    fun execute(agentPlan: AgentPlan): ActionExecution =
        execute(agentPlan, AgentContext(agentPlan.request, agentPlan.taskType))
}
