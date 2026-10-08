package com.ibra.universalagent

data class AgentResult(val success: Boolean, val message: String)

class AgentCore(
    private val brainEngine: BrainEngine,
    private val plannerEngine: PlannerEngine,
    private val actionEngine: ActionEngine,
    private val observationEngine: ObservationEngine,
    private val verificationEngine: VerificationEngine
) {
    fun process(request: String): AgentResult {
        val clean = request.trim()
        if (clean.isEmpty()) return AgentResult(false, "Je n'ai pas reçu de demande.")

        val decision = brainEngine.understand(clean)
        val agentPlan = plannerEngine.createPlan(decision)

        if (agentPlan.requiresConfirmation) {
            return AgentResult(
                true,
                "J'ai compris la demande (" + decision.taskType + "). Cette action nécessite ta confirmation avant exécution."
            )
        }

        val execution = actionEngine.execute(agentPlan)
        val observation = observationEngine.observe(execution)
        val verification = verificationEngine.verify(execution)

        val finalSuccess = verification.success &&
            observation.state == ObservationState.COMPLETED

        return AgentResult(
            finalSuccess,
            observation.summary + " [" + decision.taskType + ", observation=" + observation.state + "]"
        )
    }
}
