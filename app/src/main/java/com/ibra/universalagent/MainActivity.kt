package com.ibra.universalagent

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ibra.universalagent.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var geminiChatEngine: GeminiChatEngine
    private val evolutionBridge by lazy { EvolutionBridge() }

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
        geminiChatEngine = GeminiChatEngine(this)
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
        binding.textInput.text?.clear()

        // Explicit commands route to the local evolution service before normal chat handling.
        val command = clean.lowercase(Locale.ROOT)
        val asksStatus = command.contains("contrôle le reste") ||
            command.contains("controle le reste") ||
            (command.contains("contrôle") && (command.contains("évolution") || command.contains("agent"))) ||
            (command.contains("controle") && (command.contains("evolution") || command.contains("agent"))) ||
            (command.contains("vérifie") && (command.contains("évolution") || command.contains("agent"))) ||
            (command.contains("verifie") && (command.contains("evolution") || command.contains("agent"))) ||
            (command.contains("état") && command.contains("évolution")) ||
            (command.contains("etat") && command.contains("evolution"))
        val asksCycle = command.contains("lance le cycle d'évolution") ||
            command.contains("lance le cycle d'evolution") ||
            command.contains("démarre le cycle d'évolution") ||
            command.contains("demarre le cycle d'evolution") ||
            command.contains("lance la création de l'outil image") ||
            command.contains("lance la creation de l'outil image")

        if (asksStatus || asksCycle) {
            binding.status.text = if (asksCycle) "Vérification et cycle en cours…" else "Connexion au moteur d'évolution…"
            val callback: (String) -> Unit = { message ->
                runOnUiThread { showResponse(message) }
            }
            if (asksCycle) evolutionBridge.runCycle(callback) else evolutionBridge.checkStatus(callback)
            return
        }

        val decision = BrainEngine().understand(clean)
        if (decision.taskType == TaskType.CHAT) {
            if (!geminiChatEngine.hasApiKey()) showGeminiKeyDialog(clean) else askGemini(clean)
            return
        }

        showResponse(agentCore.process(clean).message)
    }

    private fun askGemini(request: String) {
        binding.status.text = "IBRA réfléchit…"
        geminiChatEngine.ask(request) { answer -> runOnUiThread { showResponse(answer) } }
    }

    private fun showResponse(message: String) {
        binding.conversation.append("\nIBRA : $message")
        textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ibra-response")
        binding.status.text = "Prêt"
    }

    private fun showGeminiKeyDialog(request: String) {
        val input = EditText(this).apply {
            hint = "Colle ta clé API Gemini"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Activer le cerveau IA d'IBRA")
            .setMessage("IBRA a besoin d'une clé API Gemini pour répondre aux questions normales. Elle reste enregistrée sur ce téléphone.")
            .setView(input)
            .setNegativeButton("Plus tard", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) { geminiChatEngine.saveApiKey(key); askGemini(request) }
            }.show()
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