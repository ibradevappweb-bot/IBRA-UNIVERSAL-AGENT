package com.ibra.universalagent

data class VerificationResult(val success: Boolean, val message: String)

class VerificationEngine {
    fun verify(execution: ActionExecution): VerificationResult = if (execution.observedResult.isNotBlank()) {
        VerificationResult(true, execution.observedResult)
    } else {
        VerificationResult(false, "Je ne peux pas confirmer le résultat.")
    }
}
