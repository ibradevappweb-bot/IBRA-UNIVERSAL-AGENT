package com.ibra.universalagent

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
    private var voiceConversation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        n8nChatEngine = N8nChatEngine(this)
        textToSpeech = TextToSpeech(this, this)
        setupVoice()
        addBubble("IBRA", "Bonjour ! Je suis IBRA. Écris-moi ou appuie sur le micro pour parler.", false)
        binding.sendButton.setOnClickListener { submit(binding.textInput.text.toString()) }
        binding.listenButton.setOnClickListener { startListening() }
        binding.voiceModeButton.setOnClickListener {
            voiceConversation = !voiceConversation
            binding.voiceModeButton.text = if (voiceConversation) "Mode vocal : activé" else "Mode vocal : désactivé"
            binding.status.text = if (voiceConversation) "Mode vocal activé — parle avec le micro" else "Mode vocal désactivé"
        }
        binding.status.text = "Connecté à l'interface IBRA"
    }

    private fun setupVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.status.text = "Reconnaissance vocale indisponible sur cet appareil"
            binding.listenButton.isEnabled = false
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(VoiceController(
            onReady = { runOnUiThread { binding.status.text = "J'écoute… parle à IBRA" } },
            onResult = { text -> runOnUiThread { binding.textInput.setText(text); submit(text) } },
            onError = { message -> runOnUiThread { binding.status.text = message } }
        ))
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.status.text = "Reconnaissance vocale indisponible"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Parle à IBRA")
        }
        speechRecognizer.startListening(intent)
    }

    private fun submit(request: String) {
        val clean = request.trim()
        if (clean.isEmpty()) return
        addBubble("Vous", clean, true)
        binding.textInput.text?.clear()
        binding.sendButton.isEnabled = false
        binding.listenButton.isEnabled = false
        binding.status.text = "IBRA réfléchit…"
        n8nChatEngine.ask(clean) { answer ->
            runOnUiThread {
                addBubble("IBRA", answer, false)
                binding.sendButton.isEnabled = true
                binding.listenButton.isEnabled = true
                binding.status.text = "Prêt"
                // By default, replies are text-only. Audio is user-controlled.
                if (voiceConversation && ::textToSpeech.isInitialized) {
                    textToSpeech.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ibra-response")
                }
                binding.conversationScroll.post { binding.conversationScroll.fullScroll(View.FOCUS_DOWN) }
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
                    if (::textToSpeech.isInitialized) textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ibra-manual-read")
                }
            }
            group.addView(speak)
        }
        binding.messagesContainer.addView(group)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.FRANCE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                binding.status.text = "Voix française non disponible dans les voix installées"
            }
        }
    }

    override fun onDestroy() {
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
}
