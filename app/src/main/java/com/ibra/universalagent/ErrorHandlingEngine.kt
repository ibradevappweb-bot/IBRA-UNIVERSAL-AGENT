package com.ibra.universalagent

enum class ErrorType {
    TRANSIENT,
    ACTION_FAILED,
    OBSERVATION_UNCERTAIN,
    VERIFICATION_FAILED,
    BLOCKED,
    SAFETY_BLOCKED,
    UNKNOWN
}

data class ErrorRecord(
    val type: ErrorType,
    val message: String,
    val actionFingerprint: String? = null,
    val attempt: Int = 0
)

data class LoopProtectionDecision(
    val allowed: Boolean,
    val shouldStop: Boolean,
    val reason: String,
    val attempt: Int,
    val repeatedCount: Int
)

class ErrorHandlingEngine(
    private val maxAttempts: Int = 3,
    private val maxRepeatedAction: Int = 2
) {
    private val history = mutableListOf<String>()

    init {
        require(maxAttempts > 0) { "Le nombre maximal de tentatives doit être positif." }
        require(maxRepeatedAction > 0) { "Le nombre maximal de répétitions doit être positif." }
    }

    fun classify(observation: Observation, verification: VerificationResult): ErrorType =
        when {
            observation.state == ObservationState.BLOCKED -> ErrorType.BLOCKED
            verification.state == ObservationState.BLOCKED -> ErrorType.SAFETY_BLOCKED
            observation.state == ObservationState.FAILED -> ErrorType.ACTION_FAILED
            observation.state == ObservationState.UNKNOWN ||
                observation.state == ObservationState.NOT_STARTED ||
                observation.state == ObservationState.IN_PROGRESS -> ErrorType.OBSERVATION_UNCERTAIN
            !verification.success -> ErrorType.VERIFICATION_FAILED
            else -> ErrorType.UNKNOWN
        }

    fun check(
        actionFingerprint: String?,
        attempt: Int,
        error: ErrorRecord? = null
    ): LoopProtectionDecision {
        val normalized = actionFingerprint?.trim()?.takeIf { it.isNotEmpty() }

        if (attempt >= maxAttempts) {
            return stop(attempt, count(normalized), "Nombre maximal de tentatives atteint.")
        }

        if (error?.type == ErrorType.SAFETY_BLOCKED || error?.type == ErrorType.BLOCKED) {
            return stop(attempt, count(normalized), "L'erreur est bloquante et ne doit pas être retentée automatiquement.")
        }

        if (normalized != null) {
            history += normalized
            val repeated = history.count { it == normalized }
            if (repeated > maxRepeatedAction) {
                return stop(attempt, repeated, "La même action se répète trop souvent : boucle détectée.")
            }
            return LoopProtectionDecision(
                allowed = true,
                shouldStop = false,
                reason = "Nouvelle tentative autorisée sans boucle détectée.",
                attempt = attempt,
                repeatedCount = repeated
            )
        }

        return LoopProtectionDecision(
            allowed = true,
            shouldStop = false,
            reason = "Aucune empreinte d'action répétée détectée.",
            attempt = attempt,
            repeatedCount = 0
        )
    }

    fun reset() {
        history.clear()
    }

    fun historySize(): Int = history.size

    private fun count(fingerprint: String?): Int =
        fingerprint?.let { value -> history.count { it == value } } ?: 0

    private fun stop(attempt: Int, repeatedCount: Int, reason: String) =
        LoopProtectionDecision(
            allowed = false,
            shouldStop = true,
            reason = reason,
            attempt = attempt,
            repeatedCount = repeatedCount
        )
}
