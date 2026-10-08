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

    private val agentCore by lazy {
        AgentCore(
            brainEngine = BrainEngine(),
            plannerEngine = PlannerEngine(),
            actionEngine = ActionEngine(AndroidActionExecutor(this)),
            observationEngine = ObservationEngine(),
            verificationEngine = VerificationEngine(),
            correctionEngine = CorrectionEngine()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        textToSpeech = TextToSpeech(this, this)
        setupVoice()
        binding.sendButton.setOnClickListener { submit(binding.textInput.text.toString()) }
        binding.listenButton.setOnClickListener { startListening() }
    }

    private fun setupVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.status.text = "Reconnaissance vocale indisponible"
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(VoiceController(
            onReady = { binding.status.text = "J'écoute…" },
            onResult = { text -> binding.textInput.setText(text); submit(text) },
            onError = { binding.status.text = it }
        ))
    }

    private fun startListening() {
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
        val result = agentCore.process(clean)
        binding.conversation.append("\nIBRA : " + result.message)
        textToSpeech.speak(result.message, TextToSpeech.QUEUE_FLUSH, null, "ibra-response")
        binding.textInput.text?.clear()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) textToSpeech.language = Locale.FRENCH
    }

    override fun onDestroy() {
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::textToSpeech.isInitialized) { textToSpeech.stop(); textToSpeech.shutdown() }
        super.onDestroy()
    }
}