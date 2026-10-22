package com.ibra.universalagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Draft d'écran vocal autonome IBRA.
 * Le webhook reprend l'endpoint n8n déjà utilisé par N8nChatEngine.
 */
class IbraChatActivity : Activity(), TextToSpeech.OnInitListener {
    companion object {
        const val WEBHOOK_URL =
            "http://127.0.0.1:5678/webhook/37754754-7385-4215-ae07-d80d2503cf80/chat?action=sendMessage"
        private const val REQ_MIC = 42
        private const val WRAP = -2
        private const val MATCH = -1
        private const val PURPLE = 0xFF6B4FA0.toInt()
        private const val INK = 0xFF111111.toInt()
        private const val GREY = 0xFF8A8A8A.toInt()
        private const val FIELD = 0xFFF4F4F4.toInt()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val sessionId = UUID.randomUUID().toString()
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var btnMic: TextView
    private lateinit var btnSend: TextView
    private lateinit var btnLive: TextView
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var busy = false
    private var speaking = false
    private var live = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun circle(label: String, bg: Int, fg: Int) = TextView(this).apply {
        text = label; textSize = 20f; gravity = Gravity.CENTER; setTextColor(fg)
        background = shape(bg, 22)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tts = TextToSpeech(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(buildHeader())
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(chat)
        root.addView(scroll)
        root.addView(buildInputBar())
        setContentView(root)
        addAssistant("Bonjour, je suis IBRA. Écris-moi, utilise le micro ou active Live.", false)
    }

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        col.addView(TextView(this).apply {
            text = "IBRA"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(INK)
        })
        status = TextView(this).apply { text = "Prêt"; textSize = 12f; setTextColor(GREY) }
        col.addView(status)
        btnLive = TextView(this).apply {
            text = "● Live"; textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { toggleLive() }
        }
        bar.addView(col); bar.addView(btnLive); styleLive()
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(bar)
        wrap.addView(View(this).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
        })
        return wrap
    }

    private fun buildInputBar(): View {
        val outer = LinearLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(12)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            background = shape(FIELD, 28); setPadding(dp(12), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        input = EditText(this).apply {
            hint = "Écris à IBRA…"; textSize = 16f; setTextColor(INK); background = null
            maxLines = 5; setPadding(dp(4), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        btnMic = circle("🎤", 0xFFE4E4E4.toInt(), INK).apply { setOnClickListener { toggleMic() } }
        btnSend = circle("↑", INK, Color.WHITE).apply {
            setOnClickListener { send(input.text.toString()) }
        }
        box.addView(input); box.addView(btnMic); box.addView(btnSend); outer.addView(box)
        return outer
    }

    private fun addUser(text: String) {
        val v = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(INK)
            background = shape(0xFFF0F0F0.toInt(), 20); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        chat.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.END; topMargin = dp(14); leftMargin = dp(56)
        })
        down()
    }

    private fun addAssistant(text: String, withListen: Boolean = true) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            this.text = text.replace("**", ""); textSize = 16f
            setTextColor(0xFF1A1A1A.toInt()); setTextIsSelectable(true)
        })
        if (withListen) col.addView(TextView(this).apply {
            this.text = "🔊 Écouter"; textSize = 13f; setTextColor(GREY)
            setPadding(0, dp(8), dp(12), dp(4)); setOnClickListener { speak(text) }
        })
        chat.addView(col, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(14); rightMargin = dp(24)
        })
        down()
    }

    private fun down() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    private fun setStatus(text: String) { status.text = text }
    private fun styleLive() {
        btnLive.background = shape(if (live) PURPLE else FIELD, 20)
        btnLive.setTextColor(if (live) Color.WHITE else 0xFF333333.toInt())
    }
    private fun setMicState() {
        btnMic.text = if (listening) "⏹" else "🎤"
        btnMic.background = shape(if (listening) 0xFFFFD6D6.toInt() else 0xFFE4E4E4.toInt(), 22)
    }

    private fun toggleMic() {
        if (listening) { recognizer?.stopListening(); return }
        tts?.stop(); speaking = false; startListening()
    }
    private fun toggleLive() {
        live = !live; styleLive()
        if (live) { tts?.stop(); speaking = false; startListening() }
        else {
            recognizer?.cancel(); tts?.stop(); listening = false; speaking = false
            setMicState(); setStatus("Prêt")
        }
    }
    private fun listenSoon() {
        if (live) ui.postDelayed({
            if (live && !listening && !busy && !speaking) startListening()
        }, 600)
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("La reconnaissance vocale n’est pas disponible.")
            live = false; styleLive(); return
        }
        if (busy || speaking) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    listening = false; setMicState()
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (live) { if (text.isBlank()) listenSoon() else send(text) }
                    else if (text.isNotBlank()) { input.setText(text); setStatus("Texte reconnu — appuie sur ↑") }
                }
                override fun onError(error: Int) {
                    listening = false; setMicState()
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        live = false; styleLive()
                    }
                    setStatus(if (live) "Live — nouvelle écoute" else "Prêt")
                    listenSoon()
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        listening = true; setMicState(); setStatus("J’écoute…")
        recognizer?.startListening(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else { live = false; styleLive(); toast("Autorise le micro pour parler à IBRA.") }
    }

    private fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || busy) return
        recognizer?.cancel(); listening = false; setMicState()
        addUser(msg); input.setText(""); busy = true; setStatus("IBRA réfléchit…")
        Thread {
            val result = runCatching { callAgent(msg) }
            ui.post {
                busy = false
                result.onSuccess { reply ->
                    addAssistant(reply); setStatus("Réponse reçue")
                    if (live) speak(reply)
                }.onFailure { error ->
                    addAssistant("⚠️ Impossible de joindre n8n : ${error.message}. Vérifie que le serveur est démarré.", false)
                    setStatus("Hors ligne"); listenSoon()
                }
            }
        }.start()
    }

    private fun callAgent(message: String): String {
        val connection = (URL(WEBHOOK_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 180000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = JSONObject().put("chatInput", message).put("sessionId", sessionId).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            return extract(raw).ifBlank { "(réponse vide)" }
        } finally { connection.disconnect() }
    }

    private fun extract(raw: String): String {
        val keys = listOf("output", "reply", "response", "text", "message", "answer")
        return try {
            val value = raw.trim()
            val objects = when {
                value.startsWith("[") -> {
                    val array = JSONArray(value)
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                }
                value.startsWith("{") -> listOf(JSONObject(value))
                else -> return value
            }
            for (obj in objects) {
                for (key in keys) {
                    val candidate = obj.optString(key, "")
                    if (candidate.isNotBlank()) return candidate
                }
            }
            value
        } catch (_: Exception) { raw }
    }

    override fun onInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) return
        val languageResult = tts?.setLanguage(Locale.FRENCH)
        ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                ui.post { speaking = true; setStatus("IBRA parle…") }
            }
            override fun onDone(utteranceId: String?) {
                ui.post { speaking = false; setStatus(if (live) "Live" else "Prêt"); listenSoon() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                ui.post { speaking = false; setStatus("Prêt"); listenSoon() }
            }
        })
    }

    private fun speak(text: String) {
        if (!ttsReady) { toast("La voix n’est pas encore prête."); return }
        recognizer?.cancel(); listening = false; setMicState()
        val plain = text.replace(Regex("[*#`_>]"), "")
            .take(TextToSpeech.getMaxSpeechInputLength() - 1)
        tts?.speak(plain, TextToSpeech.QUEUE_FLUSH, null, "ibra-${System.currentTimeMillis()}")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
