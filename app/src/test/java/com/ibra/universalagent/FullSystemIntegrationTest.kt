package com.ibra.universalagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullSystemIntegrationTest {

    @Test
    fun emptyRequestFailsSafely() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process("   ")

        assertFalse(result.success)
        assertEquals(AgentState.IDLE, engine.currentState().state)
    }

    @Test
    fun safeRequestCompletesThroughFullPipeline() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process("prépare cette demande")

        assertTrue(result.success)
        assertEquals(AgentState.COMPLETED, engine.currentState().state)
        assertTrue(engine.recallMemory().isNotEmpty())
    }

    @Test
    fun sensitiveRequestWaitsForConfirmation() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process("envoie un message à contact")

        assertTrue(result.success)
        assertEquals(AgentState.WAITING_CONFIRMATION, engine.currentState().state)
        assertTrue(result.message.contains("confirmation", ignoreCase = true))
    }

    @Test
    fun highRiskRequestIsBlockedBeforeExecution() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process("contourner l'authentification")

        assertFalse(result.success)
        assertEquals(AgentState.BLOCKED, engine.currentState().state)
    }

    @Test
    fun confirmedSensitiveRequestCannotPretendSuccessWithoutAndroidConnector() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process(
            "envoie un message à contact",
            confirmed = true
        )

        assertFalse(result.success)
        assertEquals(AgentState.BLOCKED, engine.currentState().state)
        assertTrue(result.message.contains("Arrêt sécurisé", ignoreCase = true))
    }

    @Test
    fun memoryContainsLastRequestAndOutcome() {
        val engine = CentralOrchestratorEngine()

        engine.process("prépare cette demande")

        val memory = engine.recallMemory()

        assertTrue(memory.any { it.key == "last_request" })
        assertTrue(memory.any { it.key == "last_outcome" })
    }

    @Test
    fun repeatedExecutionStopsSafelyInsteadOfLoopingForever() {
        val engine = CentralOrchestratorEngine(maxCycles = 3)

        val result = engine.process("envoie un message à contact", confirmed = true)

        assertFalse(result.success)
        assertEquals(AgentState.BLOCKED, engine.currentState().state)
        assertTrue(result.message.contains("sécurisé", ignoreCase = true))
    }
}
