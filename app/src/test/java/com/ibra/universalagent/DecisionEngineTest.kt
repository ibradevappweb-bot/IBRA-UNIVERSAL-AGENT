package com.ibra.universalagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionEngineTest {

    private val engine = DecisionEngine()

    private val plan = AgentPlan(
        request = "ouvrir WhatsApp",
        taskType = TaskType.OPEN_APP,
        steps = listOf(
            PlanStep(1, PlanAction.OPEN_APP, "WhatsApp", emptyMap())
        ),
        requiresConfirmation = false
    )

    @Test
    fun requestsConfirmationForSensitivePlan() {
        val sensitivePlan = plan.copy(requiresConfirmation = true)
        val decision = engine.decide(
            sensitivePlan,
            completedObservation(),
            successVerification()
        )

        assertEquals(DecisionType.REQUEST_CONFIRMATION, decision.type)
    }

    @Test
    fun executesWhenResultIsVerified() {
        val decision = engine.decide(
            plan,
            completedObservation(),
            successVerification()
        )

        assertEquals(DecisionType.EXECUTE, decision.type)
        assertTrue(decision.confidence >= 0.98)
    }

    @Test
    fun observesAgainWhenStateIsUnknown() {
        val decision = engine.decide(
            plan,
            Observation(
                state = ObservationState.UNKNOWN,
                source = ObservationSource.ACTION_ENGINE,
                summary = "Résultat inconnu"
            ),
            VerificationResult(false, "Impossible de confirmer", ObservationState.UNKNOWN),
            listOf(CorrectionStrategy.OBSERVE_AGAIN)
        )

        assertEquals(DecisionType.OBSERVE_AGAIN, decision.type)
    }

    @Test
    fun retriesFailedActionWhenRetryIsAvailable() {
        val decision = engine.decide(
            plan,
            Observation(
                state = ObservationState.FAILED,
                source = ObservationSource.ACTION_ENGINE,
                summary = "Échec"
            ),
            VerificationResult(false, "Échec", ObservationState.FAILED),
            listOf(CorrectionStrategy.RETRY)
        )

        assertEquals(DecisionType.RETRY, decision.type)
    }

    @Test
    fun stopsSafelyWhenNoSafeCorrectionExists() {
        val decision = engine.decide(
            plan,
            Observation(
                state = ObservationState.FAILED,
                source = ObservationSource.ACTION_ENGINE,
                summary = "Échec"
            ),
            VerificationResult(false, "Échec", ObservationState.FAILED)
        )

        assertEquals(DecisionType.STOP_SAFELY, decision.type)
    }

    private fun completedObservation() = Observation(
        state = ObservationState.COMPLETED,
        source = ObservationSource.ACTION_ENGINE,
        summary = "Action terminée",
        confidence = 0.95
    )

    private fun successVerification() =
        VerificationResult(true, "Résultat vérifié", ObservationState.COMPLETED)
}
