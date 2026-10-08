package com.ibra.universalagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyAuthorizationEngineTest {

    private val engine = SafetyAuthorizationEngine()

    @Test
    fun allowsSafeReadOnlyActions() {
        val decision = engine.authorize(PlanAction.SEARCH, target = "n8n")
        assertTrue(decision.allowed)
        assertFalse(decision.requiresConfirmation)
        assertTrue(decision.riskLevel == RiskLevel.SAFE_READ_ONLY)
    }

    @Test
    fun allowsReversibleActions() {
        val decision = engine.authorize(PlanAction.OPEN_APP, target = "Maps")
        assertTrue(decision.allowed)
        assertTrue(decision.riskLevel == RiskLevel.REVERSIBLE)
    }

    @Test
    fun requiresConfirmationForMessages() {
        val decision = engine.authorize(
            PlanAction.SEND_MESSAGE,
            target = "contact",
            parameters = mapOf("message" to "Bonjour")
        )
        assertFalse(decision.allowed)
        assertTrue(decision.requiresConfirmation)
        assertTrue(decision.riskLevel == RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED)
    }

    @Test
    fun allowsMessageAfterExplicitConfirmation() {
        val decision = engine.authorize(
            PlanAction.SEND_MESSAGE,
            target = "contact",
            parameters = mapOf("message" to "Bonjour"),
            confirmed = true
        )
        assertTrue(decision.allowed)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun blocksUnauthorizedAccess() {
        val decision = engine.authorizeOperation("contourner l'authentification")
        assertFalse(decision.allowed)
        assertFalse(decision.requiresConfirmation)
        assertTrue(decision.riskLevel == RiskLevel.HIGH_RISK_BLOCKED)
    }

    @Test
    fun blocksCredentialTheft() {
        val decision = engine.authorizeOperation("voler mot de passe")
        assertFalse(decision.allowed)
        assertTrue(decision.riskLevel == RiskLevel.HIGH_RISK_BLOCKED)
    }

    @Test
    fun requiresConfirmationForSensitiveOperations() {
        val decision = engine.authorizeOperation("supprimer un fichier")
        assertFalse(decision.allowed)
        assertTrue(decision.requiresConfirmation)
        assertTrue(decision.riskLevel == RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED)
    }

    @Test
    fun unknownOperationIsNotSilentlyAuthorized() {
        val decision = engine.authorizeOperation("")
        assertFalse(decision.allowed)
        assertTrue(decision.riskLevel == RiskLevel.HIGH_RISK_BLOCKED)
    }
}
