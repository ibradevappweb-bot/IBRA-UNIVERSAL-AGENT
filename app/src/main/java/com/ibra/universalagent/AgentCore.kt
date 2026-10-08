package com.ibra.universalagent

data class AgentResult(val success: Boolean, val message: String)

class AgentCore(
    brainEngine: BrainEngine = BrainEngine(),
    plannerEngine: PlannerEngine = PlannerEngine(),
    actionEngine: ActionEngine = ActionEngine(),
    observationEngine: ObservationEngine = ObservationEngine(),
    verificationEngine: VerificationEngine = VerificationEngine(),
    correctionEngine: CorrectionEngine = CorrectionEngine(),
    stateEngine: AgentStateEngine = AgentStateEngine(),
    memoryEngine: MemoryEngine = MemoryEngine()
) {
    private val orchestrator = CentralOrchestratorEngine(
        brainEngine = brainEngine,
        plannerEngine = plannerEngine,
        executionCorrectionEngine = ExecutionCorrectionEngine(
            actionEngine = actionEngine,
            observationEngine = observationEngine,
            verificationEngine = verificationEngine,
            correctionEngine = correctionEngine
        ),
        stateEngine = stateEngine,
        memoryEngine = memoryEngine
    )

    fun process(request: String): AgentResult = orchestrator.process(request)

    fun currentState(): AgentStateSnapshot = orchestrator.currentState()

    fun recallMemory(type: MemoryType? = null): List<MemoryEntry> =
        orchestrator.recallMemory(type)
}
