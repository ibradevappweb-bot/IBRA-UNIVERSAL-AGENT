package com.ibra.universalagent

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

class VoiceController(private val onReady: () -> Unit, private val onResult: (String) -> Unit, private val onError: (String) -> Unit) : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) = onReady()
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) onResult(text) else onError("Je n'ai pas compris.")
    }
    override fun onError(error: Int) = onError("Reconnaissance vocale : erreur $error")
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onRmsChanged(rmsdB: Float) {}
}
