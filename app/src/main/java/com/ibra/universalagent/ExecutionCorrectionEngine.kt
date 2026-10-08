package com.ibra.universalagent

data class ExecutionCycleResult(
    val status: ExecutionCycleStatus,
    val decision: Decision,
    val observation: Observation,
    val verification: VerificationResult,
    val correction: CorrectionDecision?,
    val attempts: Int,
    val message: String
)

enum class ExecutionCycleStatus {
    COMPLETED,
    RETRY,
    REPLAN,
    OBSERVE_AGAIN,
    WAITING_CONFIRMATION,
    ASK_CLARIFICATION,
    STOPPED
}

class ExecutionCorrectionEngine(
    private val actionEngine: ActionEngine = ActionEngine(),
    private val observationEngine: ObservationEngine = ObservationEngine(),
    private val verificationEngine: VerificationEngine = VerificationEngine(),
    private val correctionEngine: CorrectionEngine = CorrectionEngine(),
    private val decisionEngine: DecisionEngine = DecisionEngine(),
    private val safetyEngine: SafetyAuthorizationEngine = SafetyAuthorizationEngine(),
    private val errorEngine: ErrorHandlingEngine = ErrorHandlingEngine()
) {
    fun execute(
        plan: AgentPlan,
        context: AgentContext = AgentContext(plan.request, plan.taskType),
        confirmed: Boolean = false
    ): ExecutionCycleResult {
        val firstStep = plan.steps.firstOrNull()
        if (firstStep == null) {
            val observation = Observation(
                ObservationState.FAILED,
                ObservationSource.ACTION_ENGINE,
                "Plan vide.",
                confidence = 1.0
            )
            val verification = verificationEngine.verify(observation)
            val decision = Decision(
                DecisionType.STOP_SAFELY,
                "Impossible d'exécuter un plan sans étape.",
                1.0
            )
            return result(ExecutionCycleStatus.STOPPED, decision, observation, verification, null, context.attempt, decision.reason)
        }

        val authorization = safetyEngine.authorize(
            firstStep.action,
            firstStep.target,
            firstStep.parameters,
            confirmed
        )

        if (!authorization.allowed) {
            val decisionType = if (authorization.requiresConfirmation) {
                DecisionType.REQUEST_CONFIRMATION
            } else {
                DecisionType.STOP_SAFELY
            }
            val decision = Decision(
                decisionType,
                authorization.reason,
                1.0,
                firstStep
            )
            val observation = Observation(
                if (authorization.requiresConfirmation) ObservationState.BLOCKED else ObservationState.FAILED,
                ObservationSource.SYSTEM,
                authorization.reason,
                failedStep = firstStep,
                confidence = 1.0
            )
            val verification = verificationEngine.verify(observation)
            return result(
                if (authorization.requiresConfirmation) ExecutionCycleStatus.WAITING_CONFIRMATION else ExecutionCycleStatus.STOPPED,
                decision,
                observation,
                verification,
                null,
                context.attempt,
                authorization.reason
            )
        }

        val execution = actionEngine.execute(plan, context)
        val observation = observationEngine.observe(execution, context)
        val verification = verificationEngine.verify(observation)
        val corrections = correctionEngine.propose(observation, plan)

        val options = corrections.alternatives.map { it.strategy } +
            listOfNotNull(corrections.selected?.strategy)
        val decision = decisionEngine.decide(
            plan,
            observation,
            verification,
            options,
            context
        )

        val fingerprint = firstStep.action.name + ":" + (firstStep.target ?: "") + ":" + firstStep.parameters.entries
            .sortedBy { it.key }
            .joinToString(",") { it.key + "=" + it.value }

        val error = if (verification.success) null else ErrorRecord(
            type = errorEngine.classify(observation, verification),
            message = verification.message,
            actionFingerprint = fingerprint,
            attempt = context.attempt
        )
        val loop = errorEngine.check(fingerprint, context.attempt, error)

        if (!loop.allowed || loop.shouldStop) {
            val stopped = Decision(
                DecisionType.STOP_SAFELY,
                loop.reason,
                1.0,
                firstStep
            )
            return result(
                ExecutionCycleStatus.STOPPED,
                stopped,
                observation,
                verification,
                corrections,
                context.attempt,
                loop.reason
            )
        }

        val status = when (decision.type) {
            DecisionType.EXECUTE -> ExecutionCycleStatus.COMPLETED
            DecisionType.RETRY -> ExecutionCycleStatus.RETRY
            DecisionType.REPLAN -> ExecutionCycleStatus.REPLAN
            DecisionType.OBSERVE_AGAIN -> ExecutionCycleStatus.OBSERVE_AGAIN
            DecisionType.REQUEST_CONFIRMATION -> ExecutionCycleStatus.WAITING_CONFIRMATION
            DecisionType.ASK_CLARIFICATION -> ExecutionCycleStatus.ASK_CLARIFICATION
            DecisionType.STOP_SAFELY -> ExecutionCycleStatus.STOPPED
        }

        return result(status, decision, observation, verification, corrections, context.attempt, decision.reason)
    }

    private fun result(
        status: ExecutionCycleStatus,
        decision: Decision,
        observation: Observation,
        verification: VerificationResult,
        correction: CorrectionDecision?,
        attempts: Int,
        message: String
    ) = ExecutionCycleResult(
        status, decision, observation, verification, correction, attempts, message
    )
}
