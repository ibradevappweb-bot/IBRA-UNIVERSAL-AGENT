package com.ibra.universalagent

enum class ObservationState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    BLOCKED,
    UNKNOWN
}

enum class ObservationSource {
    ACTION_ENGINE,
    ANDROID_STATE,
    ACCESSIBILITY,
    SYSTEM,
    UNKNOWN
}

data class Observation(
    val state: ObservationState,
    val source: ObservationSource,
    val summary: String,
    val details: Map<String, String> = emptyMap(),
    val completedSteps: List<PlanStep> = emptyList(),
    val failedStep: PlanStep? = null,
    val confidence: Double = 0.0
)

class ObservationEngine {
    fun observe(execution: ActionExecution, context: AgentContext): Observation {
        val contextDetails = mapOf(
            "attempt" to context.attempt.toString(),
            "fact_count" to context.facts.size.toString(),
            "memory_count" to context.memory.size.toString()
        )

        if (execution.failedStep != null) {
            return Observation(
                state = ObservationState.FAILED,
                source = ObservationSource.ACTION_ENGINE,
                summary = execution.observedResult,
                details = contextDetails + mapOf(
                    "failure_action" to execution.failedStep.action.name,
                    "request" to execution.plan.request
                ),
                completedSteps = execution.completedSteps,
                failedStep = execution.failedStep,
                confidence = 0.95
            )
        }

        if (execution.observedResult.isBlank()) {
            return Observation(
                state = ObservationState.UNKNOWN,
                source = ObservationSource.ACTION_ENGINE,
                summary = "Aucun résultat observable.",
                details = contextDetails + mapOf("request" to execution.plan.request),
                completedSteps = execution.completedSteps,
                confidence = 0.0
            )
        }

        return Observation(
            state = ObservationState.COMPLETED,
            source = ObservationSource.ACTION_ENGINE,
            summary = execution.observedResult,
            details = contextDetails + mapOf(
                "request" to execution.plan.request,
                "completed_step_count" to execution.completedSteps.size.toString()
            ),
            completedSteps = execution.completedSteps,
            confidence = 0.90
        )
    }

    fun observe(execution: ActionExecution): Observation =
        observe(execution, AgentContext(execution.plan.request))

    fun observeAndroidState(
        source: ObservationSource,
        summary: String,
        details: Map<String, String> = emptyMap(),
        confidence: Double = 0.0
    ): Observation = Observation(
        state = if (summary.isBlank()) ObservationState.UNKNOWN else ObservationState.COMPLETED,
        source = source,
        summary = summary,
        details = details,
        confidence = confidence.coerceIn(0.0, 1.0)
    )
}
