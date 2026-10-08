package com.ibra.universalagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CentralOrchestratorEngineTest {

    @Test
    fun completesSafeRequestThroughCentralOrchestrator() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process("prépare cette demande")

        assertTrue(result.success)
        assertEquals(AgentState.COMPLETED, engine.currentState().state)
    }

    @Test
    fun waitsForConfirmationBeforeSensitiveExecution() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process("envoie un message à contact")

        assertTrue(result.success)
        assertEquals(AgentState.WAITING_CONFIRMATION, engine.currentState().state)
    }

    @Test
    fun blocksHighRiskOperationBeforeExecution() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process("contourner l'authentification")

        assertFalse(result.success)
        assertEquals(AgentState.BLOCKED, engine.currentState().state)
    }

    @Test
    fun confirmedSensitiveRequestCanReachExecutionLayer() {
        val engine = CentralOrchestratorEngine()

        val result = engine.process("envoie un message à contact", confirmed = true)

        assertFalse(result.success)
        assertEquals(AgentState.FAILED, engine.currentState().state)
    }
}
