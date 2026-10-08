package com.ibra.universalagent

data class ActionPlan(val request: String, val actions: List<String>)
data class ActionExecution(val plan: ActionPlan, val observedResult: String)

class ActionEngine {
    fun plan(request: String) = ActionPlan(request, listOf("UNDERSTAND_REQUEST", "PREPARE_ACTION"))
    fun execute(plan: ActionPlan) = ActionExecution(plan, "Demande reçue et préparée : ${plan.request}")
}
