package com.ibra.universalagent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Connects the Android agent to the local Termux evolution service.
 * The Python service must be running on this same phone at 127.0.0.1:8767.
 */
class EvolutionBridge {
    private val executor = Executors.newSingleThreadExecutor()
    private val baseUrl = "http://127.0.0.1:8767"

    fun checkStatus(callback: (String) -> Unit) {
        request("GET", "/status", null, callback)
    }

    fun runCycle(callback: (String) -> Unit) {
        request("POST", "/run", JSONObject(), callback)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        callback: (String) -> Unit
    ) {
        executor.execute {
            val message = try {
                val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 3000
                    readTimeout = if (path == "/run") 120000 else 8000
                    setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }
                try {
                    if (body != null) {
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                    if (code !in 200..299) {
                        "Le service d'évolution a répondu HTTP $code : " +
                            (json.optString("error").ifBlank { raw.ifBlank { "erreur inconnue" } })
                    } else if (path == "/status") {
                        val status = json.optJSONObject("status") ?: json
                        val pending = status.optJSONArray("pending_goals")
                        val goals = (0 until (pending?.length() ?: 0)).joinToString(", ") {
                            pending?.optString(it).orEmpty()
                        }.ifBlank { "aucun objectif en attente" }
                        val last = status.optString("last_cycle_utc").takeUnless { it == "null" || it.isBlank() }
                            ?: "aucun cycle exécuté"
                        "Connexion IBRA Evolution : OK. Dernier cycle : $last. Objectifs en attente : $goals."
                    } else {
                        val cycle = json.optJSONObject("cycle") ?: json
                        val goal = cycle.optJSONObject("goal")?.optString("id")
                            ?: cycle.optString("goal_id", "objectif non précisé")
                        val state = cycle.optString("status", "statut non précisé")
                        val installed = cycle.optString("promotion", "non confirmé")
                        "Cycle d'évolution terminé. Objectif : $goal. Statut : $state. Installation : $installed. " +
                            "Consulte le rapport détaillé pour vérifier l'approbation TUTEUR et les tests."
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                "Impossible de joindre IBRA Evolution sur ce téléphone. Vérifie que le service Termux " +
                    "tourne sur 127.0.0.1:8767 et que la clé Gemini est configurée dans son terminal."
            }
            callback(message)
        }
    }
}
