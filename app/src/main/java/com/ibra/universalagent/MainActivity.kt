package com.ibra.universalagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        n8nChatEngine = N8nChatEngine(this)
        textToSpeech = TextToSpeech(this, this)
        setupVoice()
        binding.sendButton.setOnClickListener { submit(binding.textInput.text.toString()) }
        binding.listenButton.setOnClickListener { startListening() }
        binding.status.text = "Prêt — IBRA n8n"
    }

    private fun setupVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.status.text = "Reconnaissance vocale indisponible sur cet appareil"
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
        binding.conversation.append("\n\nVous : $clean")
        binding.textInput.text?.clear()
        binding.sendButton.isEnabled = false
        binding.listenButton.isEnabled = false
        binding.status.text = "IBRA réfléchit…"
        n8nChatEngine.ask(clean) { answer ->
            runOnUiThread {
                binding.conversation.append("\nIBRA : $answer")
                binding.sendButton.isEnabled = true
                binding.listenButton.isEnabled = true
                binding.status.text = "Prêt"
                if (::textToSpeech.isInitialized) {
                    textToSpeech.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ibra-response")
                }
            }
        }
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
