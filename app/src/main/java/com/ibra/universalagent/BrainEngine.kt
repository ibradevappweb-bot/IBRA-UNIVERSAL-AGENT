package com.ibra.universalagent

import java.util.Locale

enum class TaskType {
    CHAT,
    INFORMATION,
    OPEN_APP,
    SEARCH,
    MESSAGE,
    OTHER
}

data class BrainDecision(
    val taskType: TaskType,
    val normalizedRequest: String,
    val steps: List<String>,
    val requiresConfirmation: Boolean
)

class BrainEngine {
    fun understand(request: String): BrainDecision {
        val text = request.trim()
        val lower = text.lowercase(Locale.ROOT)

        val type = when {
            lower.startsWith("ouvre ") || lower.startsWith("ouvre l'application ") ||
                lower.startsWith("lance ") -> TaskType.OPEN_APP
            lower.startsWith("cherche ") || lower.startsWith("recherche ") ||
                lower.contains("sur internet") -> TaskType.SEARCH
            lower.startsWith("envoie ") || lower.startsWith("envoie un message") ||
                lower.startsWith("écris à ") -> TaskType.MESSAGE
            lower.contains("information") || lower.startsWith("qui est ") ||
                lower.startsWith("qu'est-ce que ") || lower.startsWith("quel ") ||
                lower.endsWith("?") -> TaskType.INFORMATION
            else -> TaskType.CHAT
        }

        return BrainDecision(
            taskType = type,
            normalizedRequest = text,
            steps = listOf("UNDERSTAND", "PLAN", "EXECUTE", "VERIFY"),
            requiresConfirmation = type == TaskType.MESSAGE
        )
    }
}
