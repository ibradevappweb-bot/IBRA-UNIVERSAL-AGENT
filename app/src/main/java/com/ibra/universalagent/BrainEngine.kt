package com.ibra.universalagent

import java.util.Locale

enum class TaskType {
    CHAT,
    INFORMATION,
    OPEN_APP,
    SEARCH,
    MESSAGE,
    NAVIGATION,
    OTHER
}

data class BrainDecision(
    val taskType: TaskType,
    val normalizedRequest: String,
    val target: String?,
    val parameters: Map<String, String>,
    val steps: List<String>,
    val requiresConfirmation: Boolean
)

class BrainEngine {
    fun understand(request: String): BrainDecision {
        val text = request.trim()
        if (text.isEmpty()) {
            return BrainDecision(
                TaskType.CHAT, "", null, emptyMap(), listOf("UNDERSTAND"), false
            )
        }

        val lower = text.lowercase(Locale.ROOT)

        val type = when {
            isMessage(lower) -> TaskType.MESSAGE
            isOpenApp(lower) -> TaskType.OPEN_APP
            isNavigation(lower) -> TaskType.NAVIGATION
            isSearch(lower) -> TaskType.SEARCH
            isInformation(lower) -> TaskType.INFORMATION
            else -> TaskType.CHAT
        }

        val target = when (type) {
            TaskType.OPEN_APP -> extractAfter(lower, listOf("ouvre l'application ", "ouvre ", "lance "))
            TaskType.SEARCH -> extractAfter(lower, listOf("cherche ", "recherche "))
            TaskType.MESSAGE -> extractMessageTarget(text)
            TaskType.NAVIGATION -> extractAfter(lower, listOf("va à ", "va a ", "ouvre la page "))
            else -> null
        }

        val parameters = buildParameters(text, lower, type, target)
        val steps = buildSteps(type)
        val confirmation = type == TaskType.MESSAGE

        return BrainDecision(
            taskType = type,
            normalizedRequest = text,
            target = target?.trim()?.ifEmpty { null },
            parameters = parameters,
            steps = steps,
            requiresConfirmation = confirmation
        )
    }

    private fun isMessage(text: String): Boolean =
        text.startsWith("envoie ") ||
            text.startsWith("envoie un message") ||
            text.startsWith("écris à ") ||
            text.startsWith("écrit à ")

    private fun isOpenApp(text: String): Boolean =
        text.startsWith("ouvre l'application ") ||
            text.startsWith("ouvre ") ||
            text.startsWith("lance ")

    private fun isNavigation(text: String): Boolean =
        text.startsWith("va à ") ||
            text.startsWith("va a ") ||
            text.startsWith("ouvre la page ")

    private fun isSearch(text: String): Boolean =
        text.startsWith("cherche ") ||
            text.startsWith("recherche ") ||
            text.contains("sur internet")

    private fun isInformation(text: String): Boolean =
        text.contains("information") ||
            text.startsWith("qui est ") ||
            text.startsWith("qu'est-ce que ") ||
            text.startsWith("quel ") ||
            text.startsWith("quelle ") ||
            text.endsWith("?")

    private fun extractAfter(text: String, prefixes: List<String>): String? {
        val prefix = prefixes.firstOrNull { text.startsWith(it) } ?: return null
        return text.removePrefix(prefix).trim().ifEmpty { null }
    }

    private fun extractMessageTarget(original: String): String? {
        val lower = original.lowercase(Locale.ROOT)
        val marker = when {
            lower.startsWith("écris à ") -> "écris à "
            lower.startsWith("écrit à ") -> "écrit à "
            lower.startsWith("envoie un message à ") -> "envoie un message à "
            lower.startsWith("envoie ") -> "envoie "
            else -> return null
        }
        return original.substring(marker.length).trim().substringBefore(" :").trim()
            .substringBefore(":").trim().ifEmpty { null }
    }

    private fun buildParameters(
        original: String,
        lower: String,
        type: TaskType,
        target: String?
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        if (target != null) result["target"] = target

        if (type == TaskType.MESSAGE) {
            val colon = original.indexOf(':')
            if (colon >= 0 && colon + 1 < original.length) {
                result["message"] = original.substring(colon + 1).trim()
            }
        }

        if (lower.contains("urgent")) result["priority"] = "urgent"
        if (lower.contains("demain")) result["date_hint"] = "demain"
        if (lower.contains("aujourd'hui")) result["date_hint"] = "aujourd'hui"

        return result
    }

    private fun buildSteps(type: TaskType): List<String> = when (type) {
        TaskType.CHAT -> listOf("UNDERSTAND", "RESPOND")
        TaskType.INFORMATION -> listOf("UNDERSTAND", "RETRIEVE", "VERIFY", "RESPOND")
        TaskType.SEARCH -> listOf("UNDERSTAND", "PLAN", "SEARCH", "VERIFY", "RESPOND")
        TaskType.OPEN_APP -> listOf("UNDERSTAND", "PLAN", "OPEN_APP", "VERIFY", "RESPOND")
        TaskType.NAVIGATION -> listOf("UNDERSTAND", "PLAN", "NAVIGATE", "VERIFY", "RESPOND")
        TaskType.MESSAGE -> listOf("UNDERSTAND", "PLAN", "CONFIRM", "SEND", "VERIFY", "RESPOND")
        TaskType.OTHER -> listOf("UNDERSTAND", "PLAN", "EXECUTE", "VERIFY", "RESPOND")
    }
}
