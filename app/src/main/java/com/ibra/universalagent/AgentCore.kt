package com.ibra.universalagent

data class AgentResult(val success: Boolean, val message: String)

class AgentCore(private val actionEngine: ActionEngine, private val verificationEngine: VerificationEngine) {
    fun process(request: String): AgentResult {
        if (request.trim().isEmpty()) return AgentResult(false, "Je n'ai pas reçu de demande.")
        val plan = actionEngine.plan(request.trim())
        val execution = actionEngine.execute(plan)
        val verification = verificationEngine.verify(execution)
        return AgentResult(verification.success, verification.message)
    }
}
