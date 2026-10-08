package com.ibra.universalagent

data class AgentResult(val success: Boolean, val message: String)

class AgentCore(
    private val brainEngine: BrainEngine,
    private val plannerEngine: PlannerEngine,
    private val actionEngine: ActionEngine,
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
        val verification = verificationEngine.verify(execution)

        return AgentResult(
            verification.success,
            verification.message + " [" + decision.taskType + "]"
        )
    }
}
