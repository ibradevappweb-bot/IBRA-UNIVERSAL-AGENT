package com.ibra.universalagent

data class AgentResult(val success: Boolean, val message: String)

class AgentCore(
    private val brainEngine: BrainEngine,
    private val plannerEngine: PlannerEngine,
    private val actionEngine: ActionEngine,
    private val observationEngine: ObservationEngine,
    private val verificationEngine: VerificationEngine,
    private val correctionEngine: CorrectionEngine
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

        if (observation.state != ObservationState.COMPLETED || !verification.success) {
            val correction = correctionEngine.propose(observation, agentPlan)
            val selected = correction.selected
            val message = if (selected != null) {
                "Action non confirmée. Correction proposée : " +
                    selected.strategy.name +
                    ". " + selected.reason +
                    " (" + correction.alternatives.size + " alternative(s) disponible(s))."
            } else {
                "Action non confirmée. Aucune correction sûre disponible."
            }
            return AgentResult(false, message + " [" + decision.taskType + "]")
        }

        return AgentResult(
            true,
            observation.summary + " [" + decision.taskType + ", observation=" + observation.state + "]"
        )
    }
}
