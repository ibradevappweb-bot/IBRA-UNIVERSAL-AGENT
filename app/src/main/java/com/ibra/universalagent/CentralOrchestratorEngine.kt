package com.ibra.universalagent

class CentralOrchestratorEngine(
    private val brainEngine: BrainEngine = BrainEngine(),
    private val plannerEngine: PlannerEngine = PlannerEngine(),
    private val executionCorrectionEngine: ExecutionCorrectionEngine = ExecutionCorrectionEngine(),
    private val safetyEngine: SafetyAuthorizationEngine = SafetyAuthorizationEngine(),
    private val stateEngine: AgentStateEngine = AgentStateEngine(),
    private val memoryEngine: MemoryEngine = MemoryEngine(),
    private val maxCycles: Int = 3
) {
    init {
        require(maxCycles > 0) { "Le nombre maximal de cycles doit être positif." }
    }

    fun process(request: String, confirmed: Boolean = false): AgentResult {
        val clean = request.trim()
        if (clean.isEmpty()) return AgentResult(false, "Je n'ai pas reçu de demande.")

        stateEngine.start(clean)
        memoryEngine.remember(MemoryType.SESSION, "last_request", clean)

        var decision = brainEngine.understand(clean)
        stateEngine.set(AgentState.PLANNING, decision.taskType)

        var context = AgentContext(
            request = clean,
            taskType = decision.taskType,
            target = decision.target,
            parameters = decision.parameters,
            memory = memoryEngine.recall()
        )
        memoryEngine.remember(MemoryType.TASK, "current_task", decision.taskType.name, 3)

        var plan = plannerEngine.createPlan(decision, context)

        for (step in plan.steps) {
            val authorization = safetyEngine.authorize(
                step.action,
                step.target,
                step.parameters,
                confirmed
            )

            if (!authorization.allowed) {
                val nextState = if (authorization.requiresConfirmation) {
                    AgentState.WAITING_CONFIRMATION
                } else {
                    AgentState.BLOCKED
                }
                stateEngine.set(nextState, decision.taskType, context.attempt, authorization.reason)
                memoryEngine.remember(
                    MemoryType.OUTCOME,
                    "last_outcome",
                    nextState.name + ":" + decision.taskType,
                    3
                )
                return AgentResult(
                    authorization.requiresConfirmation,
                    authorization.reason + " [" + decision.taskType + "]"
                )
            }
        }

        var cycle = 0
        while (cycle < maxCycles) {
            cycle++
            context = context.withAttempt(cycle)

            val result = executionCorrectionEngine.execute(
                plan = plan,
                context = context,
                confirmed = confirmed
            )

            when (result.status) {
                ExecutionCycleStatus.COMPLETED -> {
                    stateEngine.set(
                        AgentState.COMPLETED,
                        decision.taskType,
                        cycle,
                        result.message
                    )
                    memoryEngine.remember(
                        MemoryType.OUTCOME,
                        "last_outcome",
                        "COMPLETED:" + decision.taskType,
                        3
                    )
                    return AgentResult(
                        true,
                        result.message + " [" + decision.taskType + ", tentative=" + cycle + "]"
                    )
                }

                ExecutionCycleStatus.WAITING_CONFIRMATION -> {
                    stateEngine.set(
                        AgentState.WAITING_CONFIRMATION,
                        decision.taskType,
                        cycle,
                        result.message
                    )
                    memoryEngine.remember(
                        MemoryType.OUTCOME,
                        "last_outcome",
                        "WAITING_CONFIRMATION:" + decision.taskType,
                        3
                    )
                    return AgentResult(true, result.message + " [" + decision.taskType + "]")
                }

                ExecutionCycleStatus.ASK_CLARIFICATION -> {
                    stateEngine.set(
                        AgentState.FAILED,
                        decision.taskType,
                        cycle,
                        result.message
                    )
                    memoryEngine.remember(
                        MemoryType.OUTCOME,
                        "last_outcome",
                        "ASK_CLARIFICATION:" + decision.taskType,
                        3
                    )
                    return AgentResult(false, result.message + " [" + decision.taskType + "]")
                }

                ExecutionCycleStatus.STOPPED -> {
                    stateEngine.set(
                        AgentState.BLOCKED,
                        decision.taskType,
                        cycle,
                        result.message
                    )
                    memoryEngine.remember(
                        MemoryType.OUTCOME,
                        "last_outcome",
                        "STOPPED:" + decision.taskType,
                        4
                    )
                    return AgentResult(false, result.message + " [" + decision.taskType + "]")
                }

                ExecutionCycleStatus.RETRY,
                ExecutionCycleStatus.OBSERVE_AGAIN -> {
                    continue
                }

                ExecutionCycleStatus.REPLAN -> {
                    decision = brainEngine.understand(clean)
                    stateEngine.set(AgentState.PLANNING, decision.taskType, cycle)
                    context = context.copy(
                        taskType = decision.taskType,
                        target = decision.target,
                        parameters = decision.parameters,
                        memory = memoryEngine.recall()
                    )
                    plan = plannerEngine.createPlan(decision, context)
                }
            }
        }

        stateEngine.set(
            AgentState.FAILED,
            decision.taskType,
            maxCycles,
            "Limite de cycles atteinte."
        )
        memoryEngine.remember(
            MemoryType.OUTCOME,
            "last_outcome",
            "FAILED:" + decision.taskType + ":MAX_CYCLES",
            4
        )
        return AgentResult(
            false,
            "Échec confirmé après " + maxCycles + " cycle(s). Arrêt sécurisé. [" + decision.taskType + "]"
        )
    }

    fun currentState(): AgentStateSnapshot = stateEngine.current()

    fun recallMemory(type: MemoryType? = null): List<MemoryEntry> =
        memoryEngine.recall(type)
}
