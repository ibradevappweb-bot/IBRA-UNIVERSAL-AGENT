package com.ibra.universalagent

data class AgentResult(val success: Boolean, val message: String)

class AgentCore(
    private val brainEngine: BrainEngine,
    private val plannerEngine: PlannerEngine,
    private val actionEngine: ActionEngine,
    private val observationEngine: ObservationEngine,
    private val verificationEngine: VerificationEngine,
    private val correctionEngine: CorrectionEngine,
    private val stateEngine: AgentStateEngine = AgentStateEngine(),
    private val memoryEngine: MemoryEngine = MemoryEngine()
) {
    companion object { private const val MAX_CORRECTION_ATTEMPTS = 3 }

    fun process(request: String): AgentResult {
        val clean = request.trim()
        if (clean.isEmpty()) return AgentResult(false, "Je n'ai pas reçu de demande.")
        stateEngine.start(clean)
        memoryEngine.remember(MemoryType.SESSION, "last_request", clean)
        val decision = brainEngine.understand(clean)
        stateEngine.set(AgentState.PLANNING, decision.taskType)
        var context = AgentContext(clean, decision.taskType, decision.target, decision.parameters, memory = memoryEngine.recall())
        memoryEngine.remember(MemoryType.TASK, "current_task", decision.taskType.name, 3)
        var agentPlan = plannerEngine.createPlan(decision, context)
        if (agentPlan.requiresConfirmation) {
            stateEngine.set(AgentState.WAITING_CONFIRMATION, decision.taskType, message = "Confirmation requise.")
            memoryEngine.remember(MemoryType.OUTCOME, "last_outcome", "WAITING_CONFIRMATION", 3)
            return AgentResult(true, "J'ai compris la demande (" + decision.taskType + "). Cette action nécessite ta confirmation avant exécution.")
        }
        var attempt = 0
        while (attempt < MAX_CORRECTION_ATTEMPTS) {
            attempt++
            context = context.withAttempt(attempt)
            stateEngine.set(AgentState.EXECUTING, decision.taskType, attempt)
            val execution = actionEngine.execute(agentPlan, context)
            stateEngine.set(AgentState.OBSERVING, decision.taskType, attempt)
            val observation = observationEngine.observe(execution, context)
            stateEngine.set(AgentState.VERIFYING, decision.taskType, attempt)
            val verification = verificationEngine.verify(observation)
            if (verification.success) {
                stateEngine.set(AgentState.COMPLETED, decision.taskType, attempt, verification.message)
                memoryEngine.remember(MemoryType.OUTCOME, "last_outcome", "COMPLETED:" + decision.taskType, 3)
                return AgentResult(true, verification.message + " [" + decision.taskType + ", observation=" + observation.state + ", tentative=" + attempt + ", memory=" + context.memory.size + "]")
            }
            stateEngine.set(AgentState.CORRECTING, decision.taskType, attempt)
            val correction = correctionEngine.propose(observation, agentPlan)
            val selected = correction.selected
            if (selected == null || !correction.shouldContinue || selected.requiresUserInput || selected.requiresConfirmation || selected.strategy == CorrectionStrategy.STOP_SAFELY) {
                val finalState = if (selected?.requiresConfirmation == true) AgentState.WAITING_CONFIRMATION else AgentState.FAILED
                stateEngine.set(finalState, decision.taskType, attempt, selected?.reason ?: "Aucune correction sûre disponible.")
                memoryEngine.remember(MemoryType.OUTCOME, "last_outcome", finalState.name + ":" + decision.taskType, 3)
                return AgentResult(false, "Action non confirmée après " + attempt + " tentative(s). " + (selected?.reason ?: "Aucune correction sûre disponible.") + " [" + decision.taskType + "]")
            }
            when (selected.strategy) {
                CorrectionStrategy.RETRY, CorrectionStrategy.RETRY_WITH_ADJUSTMENT, CorrectionStrategy.OBSERVE_AGAIN -> Unit
                CorrectionStrategy.REPLAN -> {
                    val refreshedDecision = brainEngine.understand(clean)
                    agentPlan = plannerEngine.createPlan(refreshedDecision, context)
                }
                CorrectionStrategy.ALTERNATIVE_ACTION, CorrectionStrategy.REQUEST_CLARIFICATION, CorrectionStrategy.REQUEST_CONFIRMATION, CorrectionStrategy.STOP_SAFELY -> return AgentResult(false, "Correction " + selected.strategy.name + " non exécutable automatiquement en toute sécurité.")
            }
        }
        stateEngine.set(AgentState.FAILED, decision.taskType, attempt, "Limite de correction atteinte.")
        memoryEngine.remember(MemoryType.OUTCOME, "last_outcome", "FAILED:" + decision.taskType + ":MAX_ATTEMPTS", 4)
        return AgentResult(false, "Échec confirmé après " + MAX_CORRECTION_ATTEMPTS + " tentative(s). Arrêt sécurisé. [" + decision.taskType + "]")
    }

    fun currentState(): AgentStateSnapshot = stateEngine.current()
    fun recallMemory(type: MemoryType? = null): List<MemoryEntry> = memoryEngine.recall(type)
}