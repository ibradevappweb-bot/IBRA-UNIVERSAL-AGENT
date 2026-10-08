package com.ibra.universalagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGoalEngineTest {
    @Test
    fun completesDependentStepsInOrder() {
        val engine = TaskGoalEngine()
        engine.createGoal(
            id = "goal-1",
            title = "Tâche composée",
            request = "Préparer puis exécuter",
            steps = listOf(
                GoalStep("prepare", 1, "Préparer"),
                GoalStep("execute", 2, "Exécuter", dependsOn = listOf("prepare")),
                GoalStep("verify", 3, "Vérifier", dependsOn = listOf("execute"))
            )
        )

        var goal = engine.startGoal("goal-1")
        assertEquals("prepare", goal.currentStepId)
        assertEquals(GoalStatus.IN_PROGRESS, goal.status)

        goal = engine.completeCurrentStep("goal-1", "préparé")
        assertEquals("execute", goal.currentStepId)

        goal = engine.completeCurrentStep("goal-1", "exécuté")
        assertEquals("verify", goal.currentStepId)

        goal = engine.completeCurrentStep("goal-1", "vérifié")
        assertEquals(GoalStatus.COMPLETED, goal.status)
        assertEquals(3, engine.progress("goal-1").completed)
    }

    @Test
    fun reportsBlockedStepsUntilDependencyCompletes() {
        val engine = TaskGoalEngine()
        engine.createGoal(
            id = "goal-2",
            title = "Dépendances",
            request = "Faire A puis B",
            steps = listOf(
                GoalStep("a", 1, "A"),
                GoalStep("b", 2, "B", dependsOn = listOf("a"))
            )
        )

        val progress = engine.progress("goal-2")
        assertEquals(1, progress.readySteps.size)
        assertEquals("a", progress.readySteps.first().id)
        assertEquals(1, progress.blockedSteps.size)
        assertEquals("b", progress.blockedSteps.first().id)
    }

    @Test
    fun rejectsUnknownDependency() {
        val engine = TaskGoalEngine()
        try {
            engine.createGoal(
                id = "goal-3",
                title = "Erreur",
                request = "Test",
                steps = listOf(
                    GoalStep("a", 1, "A", dependsOn = listOf("missing"))
                )
            )
            throw AssertionError("Une dépendance inconnue aurait dû être refusée.")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun rejectsCyclicDependency() {
        val engine = TaskGoalEngine()
        try {
            engine.createGoal(
                id = "goal-4",
                title = "Cycle",
                request = "Test",
                steps = listOf(
                    GoalStep("a", 1, "A", dependsOn = listOf("b")),
                    GoalStep("b", 2, "B", dependsOn = listOf("a"))
                )
            )
            throw AssertionError("Un cycle aurait dû être refusé.")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("cyclique") == true)
        }
    }
}
