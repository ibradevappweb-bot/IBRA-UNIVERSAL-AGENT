package com.ibra.universalagent

data class VerificationResult(
    val success: Boolean,
    val message: String,
    val state: ObservationState
)

class VerificationEngine {
    fun verify(observation: Observation): VerificationResult =
        when (observation.state) {
            ObservationState.COMPLETED -> VerificationResult(
                true,
                observation.summary,
                observation.state
            )

            ObservationState.FAILED,
            ObservationState.BLOCKED -> VerificationResult(
                false,
                observation.summary,
                observation.state
            )

            ObservationState.UNKNOWN,
            ObservationState.NOT_STARTED,
            ObservationState.IN_PROGRESS -> VerificationResult(
                false,
                if (observation.summary.isBlank()) {
                    "Je ne peux pas confirmer le résultat."
                } else {
                    observation.summary
                },
                observation.state
            )
        }
}
