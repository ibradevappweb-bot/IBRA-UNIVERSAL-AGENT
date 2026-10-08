package com.ibra.universalagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionCorrectionEngineTest {

    @Test
    fun stopsEmptyPlanSafely() {
        val engine = ExecutionCorrectionEngine()
        val plan = AgentPlan("test", TaskType.OTHER, emptyList(), false, emptyList())

        val result = engine.execute(plan)

        assertEquals(ExecutionCycleStatus.STOPPED, result.status)
        assertFalse(result.verification.success)
    }

    @Test
    fun completesSafePrepareAction() {
        val engine = ExecutionCorrectionEngine()
        val step = PlanStep(0, PlanAction.PREPARE, null, emptyMap())
        val plan = AgentPlan("prepare", TaskType.OTHER, listOf(step), false, emptyList())

        val result = engine.execute(plan)

        assertEquals(ExecutionCycleStatus.COMPLETED, result.status)
        assertTrue(result.verification.success)
        assertEquals(DecisionType.EXECUTE, result.decision.type)
    }

    @Test
    fun waitsForMessageConfirmation() {
        val engine = ExecutionCorrectionEngine()
        val step = PlanStep(0, PlanAction.SEND_MESSAGE, "contact", mapOf("message" to "Bonjour"))
        val plan = AgentPlan("message", TaskType.MESSAGE, listOf(step), true, emptyList())

        val result = engine.execute(plan)

        assertEquals(ExecutionCycleStatus.WAITING_CONFIRMATION, result.status)
        assertEquals(DecisionType.REQUEST_CONFIRMATION, result.decision.type)
        assertFalse(result.verification.success)
    }

    @Test
    fun blocksHighRiskOperationBeforeExecution() {
        val engine = ExecutionCorrectionEngine()
        val step = PlanStep(0, PlanAction.PREPARE, "contourner l'authentification", emptyMap())
        val plan = AgentPlan("unsafe", TaskType.OTHER, listOf(step), false, emptyList())

        val result = engine.execute(plan)

        assertEquals(ExecutionCycleStatus.STOPPED, result.status)
        assertEquals(DecisionType.STOP_SAFELY, result.decision.type)
    }
}
