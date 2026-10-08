package com.ibra.universalagent

data class AgentResult(val success: Boolean, val message: String)

class AgentCore(
    private val brainEngine: BrainEngine,
    private val plannerEngine: PlannerEngine,
    private val actionEngine: ActionEngine,
    private val observationEngine: ObservationEngine,
    private val verificationEngine: VerificationEngine,
    private val correctionEngine: CorrectionEngine,
    private val stateEngine: AgentStateEngine = AgentStateEngine()
) {
    companion object {
        private const val MAX_CORRECTION_ATTEMPTS = 3
    }

    fun process(request: String): AgentResult {
        val clean = request.trim()
        if (clean.isEmpty()) return AgentResult(false, "Je n'ai pas reçu de demande.")

        stateEngine.start(clean)

        val decision = brainEngine.understand(clean)
        stateEngine.set(AgentState.PLANNING, decision.taskType)

        var agentPlan = plannerEngine.createPlan(decision)

        if (agentPlan.requiresConfirmation) {
            stateEngine.set(
                AgentState.WAITING_CONFIRMATION,
                decision.taskType,
                message = "Confirmation requise."
            )
            return AgentResult(
                true,
                "J'ai compris la demande (" + decision.taskType +
                    "). Cette action nécessite ta confirmation avant exécution."
            )
        }

        var attempt = 0

        while (attempt < MAX_CORRECTION_ATTEMPTS) {
            attempt++
            stateEngine.set(AgentState.EXECUTING, decision.taskType, attempt)

            val execution = actionEngine.execute(agentPlan)

            stateEngine.set(AgentState.OBSERVING, decision.taskType, attempt)
            val observation = observationEngine.observe(execution)

            stateEngine.set(AgentState.VERIFYING, decision.taskType, attempt)
            val verification = verificationEngine.verify(observation)

            if (verification.success) {
                stateEngine.set(
                    AgentState.COMPLETED,
                    decision.taskType,
                    attempt,
                    verification.message
                )
                return AgentResult(
                    true,
                    verification.message +
                        " [" + decision.taskType +
                        ", observation=" + observation.state +
                        ", tentative=" + attempt + "]"
                )
            }

            stateEngine.set(AgentState.CORRECTING, decision.taskType, attempt)
            val correction = correctionEngine.propose(observation, agentPlan)
            val selected = correction.selected

            if (selected == null ||
                !correction.shouldContinue ||
                selected.requiresUserInput ||
                selected.requiresConfirmation ||
                selected.strategy == CorrectionStrategy.STOP_SAFELY
            ) {
                stateEngine.set(
                    if (selected?.requiresConfirmation == true) AgentState.WAITING_CONFIRMATION
                    else AgentState.FAILED,
                    decision.taskType,
                    attempt,
                    selected?.reason ?: "Aucune correction sûre disponible."
                )
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
                CorrectionStrategy.RETRY_WITH_ADJUSTMENT,
                CorrectionStrategy.OBSERVE_AGAIN -> Unit

                CorrectionStrategy.REPLAN -> {
                    val refreshedDecision = brainEngine.understand(clean)
                    agentPlan = plannerEngine.createPlan(refreshedDecision)
                }

                CorrectionStrategy.ALTERNATIVE_ACTION,
                CorrectionStrategy.REQUEST_CLARIFICATION,
                CorrectionStrategy.REQUEST_CONFIRMATION,
                CorrectionStrategy.STOP_SAFELY -> {
                    stateEngine.set(
                        AgentState.BLOCKED,
                        decision.taskType,
                        attempt,
                        "Correction non exécutable automatiquement."
                    )
                    return AgentResult(
                        false,
                        "Correction " + selected.strategy.name +
                            " non exécutable automatiquement en toute sécurité."
                    )
                }
            }
        }

        stateEngine.set(
            AgentState.FAILED,
            decision.taskType,
            attempt,
            "Limite de correction atteinte."
        )
        return AgentResult(
            false,
            "Échec confirmé après " + MAX_CORRECTION_ATTEMPTS +
                " tentative(s). Arrêt sécurisé. [" + decision.taskType + "]"
        )
    }

    fun currentState(): AgentStateSnapshot = stateEngine.current()
}
