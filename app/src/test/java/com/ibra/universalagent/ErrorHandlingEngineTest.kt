package com.ibra.universalagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorHandlingEngineTest {

    @Test
    fun classifiesBlockedObservation() {
        val engine = ErrorHandlingEngine()
        val observation = Observation(
            ObservationState.BLOCKED,
            ObservationSource.ACTION_ENGINE,
            "bloqué"
        )
        val verification = VerificationResult(false, "bloqué", ObservationState.BLOCKED)

        assertEquals(ErrorType.BLOCKED, engine.classify(observation, verification))
    }

    @Test
    fun allowsFirstAttempts() {
        val engine = ErrorHandlingEngine(maxAttempts = 3)
        val decision = engine.check("OPEN_APP:Maps", attempt = 0)

        assertTrue(decision.allowed)
        assertFalse(decision.shouldStop)
        assertEquals(1, decision.repeatedCount)
    }

    @Test
    fun stopsAfterMaximumAttempts() {
        val engine = ErrorHandlingEngine(maxAttempts = 3)
        val decision = engine.check("SEARCH:n8n", attempt = 3)

        assertFalse(decision.allowed)
        assertTrue(decision.shouldStop)
    }

    @Test
    fun detectsRepeatedActionLoop() {
        val engine = ErrorHandlingEngine(maxRepeatedAction = 2)

        engine.check("OPEN_APP:Maps", 0)
        engine.check("OPEN_APP:Maps", 1)
        val decision = engine.check("OPEN_APP:Maps", 2)

        assertFalse(decision.allowed)
        assertTrue(decision.shouldStop)
        assertEquals(3, decision.repeatedCount)
    }

    @Test
    fun blocksAutomaticRetryForSafetyError() {
        val engine = ErrorHandlingEngine()
        val error = ErrorRecord(
            type = ErrorType.SAFETY_BLOCKED,
            message = "Action bloquée"
        )

        val decision = engine.check("DELETE:file", attempt = 0, error = error)

        assertFalse(decision.allowed)
        assertTrue(decision.shouldStop)
    }

    @Test
    fun resetClearsLoopHistory() {
        val engine = ErrorHandlingEngine(maxRepeatedAction = 1)

        engine.check("A", 0)
        engine.reset()
        val decision = engine.check("A", 1)

        assertTrue(decision.allowed)
        assertEquals(1, decision.repeatedCount)
    }
}
