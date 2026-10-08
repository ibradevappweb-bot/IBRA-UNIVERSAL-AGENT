package com.ibra.universalagent

enum class CorrectionStrategy {
    RETRY,
    OBSERVE_AGAIN,
    RETRY_WITH_ADJUSTMENT,
    ALTERNATIVE_ACTION,
    REPLAN,
    REQUEST_CLARIFICATION,
    REQUEST_CONFIRMATION,
    STOP_SAFELY
}

data class CorrectionOption(
    val strategy: CorrectionStrategy,
    val priority: Int,
    val reason: String,
    val proposedAction: String?,
    val requiresUserInput: Boolean,
    val requiresConfirmation: Boolean
)

data class CorrectionDecision(
    val shouldContinue: Boolean,
    val selected: CorrectionOption?,
    val alternatives: List<CorrectionOption>,
    val explanation: String
)

class CorrectionEngine {
    fun propose(observation: Observation, plan: AgentPlan): CorrectionDecision {
        if (observation.state == ObservationState.COMPLETED) {
            return CorrectionDecision(
                shouldContinue = false,
                selected = null,
                alternatives = emptyList(),
                explanation = "Aucune correction nécessaire : le résultat est observé comme terminé."
            )
        }

        val options = buildOptions(observation, plan).sortedBy { it.priority }
        val selected = options.firstOrNull {
            !it.requiresUserInput && !it.requiresConfirmation
        } ?: options.firstOrNull()

        return CorrectionDecision(
            shouldContinue = selected != null && selected.strategy != CorrectionStrategy.STOP_SAFELY,
            selected = selected,
            alternatives = options.drop(1),
            explanation = if (selected != null) {
                "Une correction est proposée avec " + options.size + " option(s)."
            } else {
                "Aucune correction sûre n'est disponible."
            }
        )
    }

    private fun buildOptions(
        observation: Observation,
        plan: AgentPlan
    ): List<CorrectionOption> {
        val failed = observation.failedStep

        if (observation.state == ObservationState.UNKNOWN) {
            return listOf(
                CorrectionOption(
                    CorrectionStrategy.OBSERVE_AGAIN, 1,
                    "Réobserver avant de refaire une action évite une double exécution.",
                    null, false, false
                ),
                CorrectionOption(
                    CorrectionStrategy.RETRY, 2,
                    "Réessayer lorsque le résultat reste réellement inconnu.",
                    failed?.action?.name, false, false
                ),
                CorrectionOption(
                    CorrectionStrategy.REQUEST_CONFIRMATION, 3,
                    "Demander confirmation si l'état réel reste ambigu.",
                    null, true, true
                ),
                CorrectionOption(
                    CorrectionStrategy.STOP_SAFELY, 4,
                    "Arrêter sans provoquer d'action supplémentaire.",
                    null, false, false
                )
            )
        }

        return listOf(
            CorrectionOption(
                CorrectionStrategy.RETRY, 1,
                "Réessayer exactement l'étape échouée.",
                failed?.action?.name, false, false
            ),
            CorrectionOption(
                CorrectionStrategy.RETRY_WITH_ADJUSTMENT, 2,
                "Réessayer avec les paramètres ou conditions ajustés.",
                failed?.action?.name, false, false
            ),
            CorrectionOption(
                CorrectionStrategy.ALTERNATIVE_ACTION, 3,
                "Utiliser une autre méthode compatible avec le même objectif.",
                alternativeFor(failed?.action), false, false
            ),
            CorrectionOption(
                CorrectionStrategy.REPLAN, 4,
                "Reconstruire le plan à partir de l'état réellement observé.",
                plan.taskType.name, false, false
            ),
            CorrectionOption(
                CorrectionStrategy.REQUEST_CLARIFICATION, 5,
                "Demander une précision lorsque plusieurs interprétations sont possibles.",
                null, true, false
            ),
            CorrectionOption(
                CorrectionStrategy.REQUEST_CONFIRMATION, 6,
                "Demander l'accord du client avant une action sensible.",
                failed?.action?.name, true, true
            ),
            CorrectionOption(
                CorrectionStrategy.STOP_SAFELY, 7,
                "Arrêter proprement plutôt que d'inventer une réussite.",
                null, false, false
            )
        )
    }

    private fun alternativeFor(action: PlanAction?): String? = when (action) {
        PlanAction.OPEN_APP -> "ANDROID_INTENT_OR_PACKAGE"
        PlanAction.SEARCH -> "BROWSER_OR_NATIVE_SEARCH"
        PlanAction.NAVIGATE -> "ANDROID_INTENT_OR_ACCESSIBILITY"
        PlanAction.SEND_MESSAGE -> "OFFICIAL_API_OR_ANDROID_INTENT"
        PlanAction.PREPARE -> "REPLAN"
        PlanAction.RESPOND -> "RESPOND_WITH_FAILURE"
        null -> null
    }
}
