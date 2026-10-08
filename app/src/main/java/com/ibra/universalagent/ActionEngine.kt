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

class ActionEngine {
    fun plan(agentPlan: AgentPlan): ActionPlan =
        ActionPlan(
            request = agentPlan.request,
            actions = agentPlan.steps.map { it.action.name }
        )

    fun execute(agentPlan: AgentPlan, context: AgentContext): ActionExecution {
        val completed = mutableListOf<PlanStep>()
        val contextInfo = contextMetadata(context)

        for (step in agentPlan.steps) {
            when (step.action) {
                PlanAction.PREPARE,
                PlanAction.RESPOND -> completed += step

                PlanAction.OPEN_APP,
                PlanAction.SEARCH,
                PlanAction.NAVIGATE,
                PlanAction.SEND_MESSAGE -> return ActionExecution(
                    plan = plan(agentPlan),
                    observedResult = "Action non connectée à Android : " + step.action,
                    completedSteps = completed,
                    failedStep = step,
                    contextAttempt = context.attempt,
                    contextFacts = context.facts,
                    contextMemoryKeys = context.memory.map { it.key }
                )
            }
        }

        return ActionExecution(
            plan = plan(agentPlan),
            observedResult = "Plan exécuté par le moteur : " + agentPlan.request,
            completedSteps = completed,
            contextAttempt = context.attempt,
            contextFacts = context.facts,
            contextMemoryKeys = context.memory.map { it.key }
        )
    }

    fun execute(agentPlan: AgentPlan): ActionExecution =
        execute(agentPlan, AgentContext(agentPlan.request, agentPlan.taskType))

    private fun contextMetadata(context: AgentContext): Map<String, String> =
        mapOf(
            "attempt" to context.attempt.toString(),
            "fact_count" to context.facts.size.toString(),
            "memory_count" to context.memory.size.toString()
        )
}
