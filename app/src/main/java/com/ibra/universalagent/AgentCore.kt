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
    companion object {
        private const val MAX_CORRECTION_ATTEMPTS = 3
    }

    fun process(request: String): AgentResult {
        val clean = request.trim()
        if (clean.isEmpty()) return AgentResult(false, "Je n'ai pas reçu de demande.")

        val decision = brainEngine.understand(clean)
        var agentPlan = plannerEngine.createPlan(decision)

        if (agentPlan.requiresConfirmation) {
            return AgentResult(
                true,
                "J'ai compris la demande (" + decision.taskType + "). Cette action nécessite ta confirmation avant exécution."
            )
        }

        var attempt = 0

        while (attempt < MAX_CORRECTION_ATTEMPTS) {
            attempt++

            val execution = actionEngine.execute(agentPlan)
            val observation = observationEngine.observe(execution)
            val verification = verificationEngine.verify(observation)

            if (verification.success) {
                return AgentResult(
                    true,
                    verification.message +
                        " [" + decision.taskType +
                        ", observation=" + observation.state +
                        ", tentative=" + attempt + "]"
                )
            }

            val correction = correctionEngine.propose(observation, agentPlan)
            val selected = correction.selected

            if (selected == null ||
                !correction.shouldContinue ||
                selected.requiresUserInput ||
                selected.requiresConfirmation ||
                selected.strategy == CorrectionStrategy.STOP_SAFELY
            ) {
                return AgentResult(
                    false,
                    "Action non confirmée après " + attempt +
                        " tentative(s). " +
                        (selected?.reason ?: "Aucune correction sûre disponible.") +
                        " [" + decision.taskType + "]"
                )
            }

            when (selected.strategy) {
                CorrectionStrategy.RETRY,
                CorrectionStrategy.RETRY_WITH_ADJUSTMENT -> {
                    // Rejoue le plan sans prétendre que l'action précédente a réussi.
                }

                CorrectionStrategy.OBSERVE_AGAIN -> {
                    // L'observation suivante est faite au début de la prochaine boucle.
                }

                CorrectionStrategy.REPLAN -> {
                    val refreshedDecision = brainEngine.understand(clean)
                    agentPlan = plannerEngine.createPlan(refreshedDecision)
                }

                CorrectionStrategy.ALTERNATIVE_ACTION,
                CorrectionStrategy.REQUEST_CLARIFICATION,
                CorrectionStrategy.REQUEST_CONFIRMATION,
                CorrectionStrategy.STOP_SAFELY -> {
                    return AgentResult(
                        false,
                        "Correction " + selected.strategy.name +
                            " non exécutable automatiquement en toute sécurité."
                    )
                }
            }
        }

        return AgentResult(
            false,
            "Échec confirmé après " + MAX_CORRECTION_ATTEMPTS +
                " tentative(s). Arrêt sécurisé. [" + decision.taskType + "]"
        )
    }
}
