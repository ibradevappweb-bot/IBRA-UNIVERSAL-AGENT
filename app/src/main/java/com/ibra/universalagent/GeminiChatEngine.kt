package com.ibra.universalagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class GeminiChatEngine(context: Context) {
    private val prefs = context.getSharedPreferences("ibra_ai", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()

    fun hasApiKey(): Boolean = prefs.getString("gemini_api_key", null)?.isNotBlank() == true
    fun saveApiKey(key: String) { prefs.edit().putString("gemini_api_key", key.trim()).apply() }

    fun ask(prompt: String, callback: (String) -> Unit) {
        val key = prefs.getString("gemini_api_key", null)?.trim()
        if (key.isNullOrEmpty()) { callback("Je peux discuter avec toi, mais ma clé IA Gemini n'est pas encore configurée."); return }
        executor.execute {
            val answer = try {
                val encodedKey = URLEncoder.encode(key, "UTF-8")
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$encodedKey")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 15000; readTimeout = 30000; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject().put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", "Tu es IBRA, un assistant Android francophone. Réponds naturellement, clairement et brièvement. Question : $prompt")
                    ))
                )).toString()
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) "Erreur du service IA ($code). Vérifie ta clé Gemini et Internet."
                else JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
            } catch (_: Exception) { "Je n'arrive pas à contacter mon moteur IA. Vérifie la connexion Internet." }
            callback(answer)
        }
    }
}