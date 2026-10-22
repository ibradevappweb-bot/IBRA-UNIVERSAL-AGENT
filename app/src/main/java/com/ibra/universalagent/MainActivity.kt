package com.ibra.universalagent

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ibra.universalagent.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var n8nChatEngine: N8nChatEngine

    private val mainHandler = Handler(Looper.getMainLooper())
    private var voiceConversation = false
    private var ttsReady = false
    private var waitingForAgent = false
    private var destroyed = false
    private var manualOneShot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        n8nChatEngine = N8nChatEngine(this)
        textToSpeech = TextToSpeech(this, this)
        setupVoice()
        addBubble("IBRA", "Bonjour ! Je suis IBRA. Écris-moi, ou active le mode vocal pour converser à la voix.", false)

        binding.sendButton.setOnClickListener { submit(binding.textInput.text.toString()) }
        binding.listenButton.setOnClickListener {
            if (voiceConversation) {
                stopVoiceConversation()
            } else {
                manualOneShot = true
                startListening()
            }
        }
        binding.voiceModeButton.setOnClickListener {
            if (voiceConversation) {
                stopVoiceConversation()
            } else {
                startVoiceConversation()
            }
        }
        binding.status.text = "Prêt"
    }

    private fun setupVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.status.text = "Reconnaissance vocale indisponible sur cet appareil"
            binding.listenButton.isEnabled = false
            binding.voiceModeButton.isEnabled = false
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(VoiceController(
            onReady = { runOnUiThread {
                if (voiceConversation || manualOneShot) binding.status.text = "J'écoute… parle à IBRA"
            } },
            onResult = { text -> runOnUiThread {
                manualOneShot = false
                if (voiceConversation) {
                    if (!waitingForAgent) {
                        waitingForAgent = true
                        submit(text)
                    }
                } else {
                    binding.textInput.setText(text)
                    submit(text)
                }
            } },
            onError = { message -> runOnUiThread {
                if (voiceConversation && !waitingForAgent && !destroyed) {
                    binding.status.text = "$message — je relance l'écoute…"
                    scheduleListening(900)
                } else {
                    binding.status.text = message
                    manualOneShot = false
                }
            } }
        ))
    }

    private fun startVoiceConversation() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.status.text = "Reconnaissance vocale indisponible"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        voiceConversation = true
        waitingForAgent = false
        binding.voiceModeButton.text = "Arrêter la conversation vocale"
        binding.listenButton.text = "Arrêter la voix"
        binding.status.text = "Mode vocal activé — je t'écoute"
        startListening()
    }

    private fun stopVoiceConversation() {
        voiceConversation = false
        waitingForAgent = false
        manualOneShot = false
        mainHandler.removeCallbacksAndMessages(null)
        if (::speechRecognizer.isInitialized) {
            try { speechRecognizer.cancel() } catch (_: Exception) {}
        }
        if (::textToSpeech.isInitialized) textToSpeech.stop()
        binding.voiceModeButton.text = "Mode vocal : désactivé"
        binding.listenButton.text = "🎙 Parler"
        binding.status.text = "Mode vocal désactivé"
    }

    private fun startListening() {
        if (destroyed || !::speechRecognizer.isInitialized) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.FRENCH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Parle à IBRA")
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (error: Exception) {
            binding.status.text = "Impossible de démarrer le micro : ${error.javaClass.simpleName}"
            if (voiceConversation) scheduleListening(1200)
        }
    }

    private fun scheduleListening(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        if (voiceConversation && !waitingForAgent && !destroyed) {
            mainHandler.postDelayed({
                if (voiceConversation && !waitingForAgent && !destroyed) startListening()
            }, delayMs)
        }
    }

    private fun submit(request: String) {
        val clean = request.trim()
        if (clean.isEmpty()) {
            waitingForAgent = false
            if (voiceConversation) scheduleListening(500)
            return
        }
        addBubble("Vous", clean, true)
        binding.textInput.text?.clear()
        binding.sendButton.isEnabled = false
        binding.listenButton.isEnabled = false
        binding.status.text = "IBRA réfléchit…"
        n8nChatEngine.ask(clean) { answer ->
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                addBubble("IBRA", answer, false)
                binding.sendButton.isEnabled = true
                binding.listenButton.isEnabled = true
                binding.status.text = "IBRA a répondu"
                binding.conversationScroll.post { binding.conversationScroll.fullScroll(View.FOCUS_DOWN) }
                if (voiceConversation && ttsReady) {
                    binding.status.text = "IBRA parle…"
                    val result = textToSpeech.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ibra-live-response")
                    if (result == TextToSpeech.ERROR) {
                        waitingForAgent = false
                        binding.status.text = "Lecture vocale impossible — reprise de l'écoute"
                        scheduleListening(700)
                    }
                } else {
                    waitingForAgent = false
                    if (voiceConversation) scheduleListening(500)
                }
            }
        }
    }

    private fun addBubble(author: String, message: String, isUser: Boolean) {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, 8, 0, 8)
        }
        val bubble = TextView(this).apply {
            text = "$author\n$message"
            textSize = 16f
            setTextColor(Color.rgb(30, 30, 30))
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(if (isUser) Color.rgb(224, 242, 228) else Color.rgb(242, 243, 245))
                cornerRadius = 28f
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        }
        group.addView(bubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (!isUser && author == "IBRA") {
            val speak = Button(this).apply {
                text = "🔊 Lire la réponse"
                textSize = 12f
                setOnClickListener {
                    if (ttsReady) textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ibra-manual-read")
                }
            }
            group.addView(speak)
        }
        binding.messagesContainer.addView(group)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.FRANCE)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "ibra-live-response") mainHandler.post {
                        if (voiceConversation && !destroyed) {
                            waitingForAgent = false
                            binding.status.text = "À toi de parler…"
                            scheduleListening(350)
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == "ibra-live-response") mainHandler.post {
                        waitingForAgent = false
                        if (voiceConversation && !destroyed) scheduleListening(700)
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
            })
            if (!ttsReady) binding.status.text = "Installe une voix française dans les paramètres Android"
        } else {
            ttsReady = false
            binding.status.text = "Synthèse vocale indisponible"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (voiceConversation) startListening() else if (manualOneShot) startListening()
            } else {
                voiceConversation = false
                manualOneShot = false
                binding.voiceModeButton.text = "Mode vocal : désactivé"
                binding.listenButton.text = "🎙 Parler"
                binding.status.text = "Autorise le microphone pour parler à IBRA"
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
}