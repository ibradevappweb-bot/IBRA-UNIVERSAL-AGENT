package com.ibra.universalagent

enum class DecisionType {
    EXECUTE,
    ASK_CLARIFICATION,
    REQUEST_CONFIRMATION,
    RETRY,
    REPLAN,
    OBSERVE_AGAIN,
    STOP_SAFELY
}

data class Decision(
    val type: DecisionType,
    val reason: String,
    val confidence: Double,
    val targetStep: PlanStep? = null
)

class DecisionEngine {

    fun decide(
        plan: AgentPlan,
        observation: Observation,
        verification: VerificationResult,
        correctionOptions: List<CorrectionStrategy> = emptyList(),
        context: AgentContext = AgentContext(plan.request, plan.taskType)
    ): Decision {
        if (plan.requiresConfirmation) {
            return Decision(
                type = DecisionType.REQUEST_CONFIRMATION,
                reason = "La tâche nécessite une confirmation explicite avant l'action sensible.",
                confidence = 1.0
            )
        }

        if (verification.success) {
            return Decision(
                type = DecisionType.EXECUTE,
                reason = "Le résultat est vérifié comme réussi.",
                confidence = verificationConfidence(verification)
            )
        }

        return when (observation.state) {
            ObservationState.UNKNOWN,
            ObservationState.NOT_STARTED,
            ObservationState.IN_PROGRESS -> chooseForUncertainState(plan, correctionOptions)

            ObservationState.FAILED,
            ObservationState.BLOCKED -> chooseForFailure(plan, correctionOptions)

            ObservationState.COMPLETED -> Decision(
                type = DecisionType.OBSERVE_AGAIN,
                reason = "Le moteur d'observation indique terminé, mais la vérification n'a pas confirmé le résultat.",
                confidence = 0.85
            )
        }
    }

    private fun chooseForUncertainState(
        plan: AgentPlan,
        options: List<CorrectionStrategy>
    ): Decision {
        val strategy = options.firstOrNull {
            it == CorrectionStrategy.OBSERVE_AGAIN
        } ?: options.firstOrNull {
            it == CorrectionStrategy.RETRY
        }

        return when (strategy) {
            CorrectionStrategy.OBSERVE_AGAIN -> Decision(
                DecisionType.OBSERVE_AGAIN,
                "Le résultat n'est pas suffisamment observable. Une nouvelle observation est nécessaire.",
                0.90
            )
            CorrectionStrategy.RETRY -> Decision(
                DecisionType.RETRY,
                "Le résultat est incertain. Une nouvelle tentative est possible.",
                0.75
            )
            else -> Decision(
                DecisionType.STOP_SAFELY,
                "Le moteur ne dispose pas d'une action de correction suffisamment sûre.",
                0.95
            )
        }
    }

    private fun chooseForFailure(
        plan: AgentPlan,
        options: List<CorrectionStrategy>
    ): Decision {
        val strategy = options.firstOrNull {
            it == CorrectionStrategy.RETRY_WITH_ADJUSTMENT
        } ?: options.firstOrNull {
            it == CorrectionStrategy.RETRY
        } ?: options.firstOrNull {
            it == CorrectionStrategy.ALTERNATIVE_ACTION
        } ?: options.firstOrNull {
            it == CorrectionStrategy.REPLAN
        } ?: options.firstOrNull {
            it == CorrectionStrategy.REQUEST_CLARIFICATION
        }

        return when (strategy) {
            CorrectionStrategy.RETRY_WITH_ADJUSTMENT -> Decision(
                DecisionType.RETRY,
                "L'action a échoué, mais une nouvelle tentative ajustée est disponible.",
                0.85
            )
            CorrectionStrategy.RETRY -> Decision(
                DecisionType.RETRY,
                "L'action a échoué et peut être retentée.",
                0.80
            )
            CorrectionStrategy.ALTERNATIVE_ACTION -> Decision(
                DecisionType.REPLAN,
                "L'action a échoué. Une alternative doit être planifiée.",
                0.80
            )
            CorrectionStrategy.REPLAN -> Decision(
                DecisionType.REPLAN,
                "Le plan actuel ne permet pas d'atteindre le résultat. Replanification nécessaire.",
                0.90
            )
            CorrectionStrategy.REQUEST_CLARIFICATION -> Decision(
                DecisionType.ASK_CLARIFICATION,
                "Les informations disponibles ne permettent pas de choisir une correction sûre.",
                0.95
            )
            else -> Decision(
                DecisionType.STOP_SAFELY,
                "Échec non récupérable avec les stratégies disponibles.",
                0.98
            )
        }
    }

    private fun verificationConfidence(verification: VerificationResult): Double =
        when (verification.state) {
            ObservationState.COMPLETED -> 0.98
            ObservationState.FAILED,
            ObservationState.BLOCKED -> 0.0
            ObservationState.UNKNOWN,
            ObservationState.NOT_STARTED,
            ObservationState.IN_PROGRESS -> 0.50
        }
}
