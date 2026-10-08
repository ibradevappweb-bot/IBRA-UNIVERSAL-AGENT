package com.ibra.universalagent

enum class RiskLevel {
    SAFE_READ_ONLY,
    REVERSIBLE,
    SENSITIVE_CONFIRMATION_REQUIRED,
    HIGH_RISK_BLOCKED
}

data class AuthorizationDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val riskLevel: RiskLevel,
    val reason: String
)

class SafetyAuthorizationEngine {

    fun authorize(
        action: PlanAction,
        target: String? = null,
        parameters: Map<String, String> = emptyMap(),
        confirmed: Boolean = false
    ): AuthorizationDecision {
        val text = buildText(action.name, target, parameters)

        if (containsBlockedOperation(text)) {
            return blocked("Opération à haut risque bloquée par la politique de sécurité.")
        }

        return when (action) {
            PlanAction.PREPARE,
            PlanAction.RESPOND,
            PlanAction.SEARCH -> safe("Action sans effet sensible sur un système externe.")

            PlanAction.OPEN_APP,
            PlanAction.NAVIGATE -> reversible("Action réversible ou sans modification durable.")

            PlanAction.SEND_MESSAGE -> {
                if (confirmed) {
                    AuthorizationDecision(
                        allowed = true,
                        requiresConfirmation = false,
                        riskLevel = RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED,
                        reason = "Confirmation explicite reçue pour l'envoi du message."
                    )
                } else {
                    AuthorizationDecision(
                        allowed = false,
                        requiresConfirmation = true,
                        riskLevel = RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED,
                        reason = "L'envoi d'un message exige une confirmation explicite."
                    )
                }
            }
        }
    }

    fun authorizeOperation(
        operation: String,
        target: String? = null,
        parameters: Map<String, String> = emptyMap(),
        confirmed: Boolean = false
    ): AuthorizationDecision {
        val text = buildText(operation, target, parameters)

        if (containsBlockedOperation(text)) {
            return blocked("Opération à haut risque bloquée par la politique de sécurité.")
        }

        if (containsSensitiveOperation(text)) {
            return if (confirmed) {
                AuthorizationDecision(
                    allowed = true,
                    requiresConfirmation = false,
                    riskLevel = RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED,
                    reason = "Confirmation explicite reçue pour l'opération sensible."
                )
            } else {
                AuthorizationDecision(
                    allowed = false,
                    requiresConfirmation = true,
                    riskLevel = RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED,
                    reason = "Cette opération sensible exige une confirmation explicite."
                )
            }
        }

        if (operation.isBlank()) {
            return blocked("Opération inconnue : impossible d'autoriser une action vide.")
        }

        return safe("Opération non sensible autorisée par défaut.")
    }

    private fun buildText(
        operation: String,
        target: String?,
        parameters: Map<String, String>
    ): String =
        (listOf(operation, target.orEmpty()) + parameters.entries.map { entry ->
            entry.key + " " + entry.value
        }).joinToString(" ").lowercase()

    private fun containsBlockedOperation(text: String): Boolean =
        blockedPatterns.any(text::contains)

    private fun containsSensitiveOperation(text: String): Boolean =
        sensitivePatterns.any(text::contains)

    private fun safe(reason: String) = AuthorizationDecision(
        allowed = true,
        requiresConfirmation = false,
        riskLevel = RiskLevel.SAFE_READ_ONLY,
        reason = reason
    )

    private fun reversible(reason: String) = AuthorizationDecision(
        allowed = true,
        requiresConfirmation = false,
        riskLevel = RiskLevel.REVERSIBLE,
        reason = reason
    )

    private fun blocked(reason: String) = AuthorizationDecision(
        allowed = false,
        requiresConfirmation = false,
        riskLevel = RiskLevel.HIGH_RISK_BLOCKED,
        reason = reason
    )

    companion object {
        private val sensitivePatterns = listOf(
            "send_message",
            "envoyer un message",
            "envoyer message",
            "purchase",
            "acheter",
            "buy",
            "transfer",
            "virement",
            "paiement",
            "payment",
            "publish",
            "publier",
            "delete",
            "supprimer",
            "account change",
            "modifier le compte",
            "security change",
            "modifier la sécurité"
        )

        private val blockedPatterns = listOf(
            "bypass authentication",
            "bypass auth",
            "contourner authentification",
            "contourner l'authentification",
            "steal credential",
            "voler identifiant",
            "voler mot de passe",
            "credential theft",
            "password theft",
            "unauthorized access",
            "accès non autorisé",
            "hack account",
            "pirater le compte",
            "disable security",
            "désactiver la sécurité",
            "disable antivirus",
            "désactiver antivirus"
        )
    }
}
