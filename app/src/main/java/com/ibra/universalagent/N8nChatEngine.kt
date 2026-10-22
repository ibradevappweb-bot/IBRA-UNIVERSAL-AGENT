package com.ibra.universalagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Connects the native voice UI to the existing n8n Chat Trigger.
 * The endpoint points to localhost because the current n8n instance runs on this same phone.
 */
class N8nChatEngine(context: Context) {
    private val prefs = context.getSharedPreferences("ibra_n8n_chat", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()

    private val endpoint =
        "http://127.0.0.1:5678/webhook/37754754-7385-4215-ae07-d80d2503cf80/chat"

    private fun sessionId(): String {
        val saved = prefs.getString("session_id", null)
        if (!saved.isNullOrBlank()) return saved
        return UUID.randomUUID().toString().also {
            prefs.edit().putString("session_id", it).apply()
        }
    }

    fun ask(prompt: String, callback: (String) -> Unit) {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            callback("Dis-moi ce que tu veux faire.")
            return
        }
        executor.execute {
            val answer = try {
                val url = URL("$endpoint?action=sendMessage")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 90000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json, text/plain, text/event-stream")
                }
                val body = JSONObject()
                    .put("chatInput", clean)
                    .put("sessionId", sessionId())
                    .toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (code !in 200..299) {
                    "Je n'arrive pas à joindre IBRA dans n8n (HTTP $code). Vérifie que n8n fonctionne sur ce téléphone et que le workflow est actif."
                } else {
                    extractReply(raw)
                }
            } catch (error: Exception) {
                "Connexion à IBRA impossible. Vérifie que n8n est lancé sur ce téléphone à l'adresse localhost:5678. Détail : ${error.javaClass.simpleName}"
            }
            callback(answer)
        }
    }

    private fun extractReply(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return "IBRA a renvoyé une réponse vide."
        return try {
            val json = if (value.startsWith("[")) {
                JSONArray(value).optJSONObject(0) ?: return value
            } else if (value.startsWith("{")) {
                JSONObject(value)
            } else {
                return value
            }
            val keys = listOf("output", "text", "response", "message", "reply", "answer")
            for (key in keys) {
                val candidate = json.opt(key)
                if (candidate is String && candidate.isNotBlank()) return candidate.trim()
            }
            val data = json.opt("data")
            if (data is JSONObject) {
                for (key in keys) {
                    val candidate = data.opt(key)
                    if (candidate is String && candidate.isNotBlank()) return candidate.trim()
                }
            }
            value
        } catch (_: Exception) {
            value
        }
    }
}
