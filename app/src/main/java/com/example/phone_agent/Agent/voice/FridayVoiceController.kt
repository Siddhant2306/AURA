package com.example.phone_agent.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class FridayVoiceController(
    context: Context,
    private val onCommand: (String) -> Unit,
) : RecognitionListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
        setRecognitionListener(this@FridayVoiceController)
    }
    private val tts: TextToSpeech

    private val _state = MutableStateFlow(FridayVoiceState())
    val state: StateFlow<FridayVoiceState> = _state

    private var ttsReady = false
    private var currentMode = FridayVoiceMode.Stopped

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts.language = Locale.getDefault()
            }
        }
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    scope.launch {
                        if (_state.value.isEnabled) startWakeWordListening()
                    }
                }

                @Deprecated("Deprecated by the Android framework; required for older API callbacks.")
                override fun onError(utteranceId: String?) {
                    scope.launch {
                        setError("I could not speak the response.")
                        restartWakeWordSoon()
                    }
                }
            },
        )
    }

    fun start() {
        _state.update { it.copy(isEnabled = true) }
        startWakeWordListening()
    }

    fun stop() {
        _state.update {
            it.copy(
                mode = FridayVoiceMode.Stopped,
                status = "Friday is stopped.",
                isEnabled = false,
                transcript = "",
                amplitude = 0f,
            )
        }
        currentMode = FridayVoiceMode.Stopped
        recognizer.cancel()
        tts.stop()
    }

    fun setThinking(command: String) {
        recognizer.cancel()
        currentMode = FridayVoiceMode.Thinking
        _state.update {
            it.copy(
                mode = FridayVoiceMode.Thinking,
                status = "Working on: $command",
                transcript = command,
                amplitude = 0.35f,
            )
        }
    }

    fun setProgress(status: String) {
        _state.update { it.copy(status = status) }
    }

    fun setError(message: String) {
        currentMode = FridayVoiceMode.Error
        _state.update {
            it.copy(
                mode = FridayVoiceMode.Error,
                status = message,
                amplitude = 0f,
            )
        }
    }

    fun speak(text: String) {
        recognizer.cancel()
        currentMode = FridayVoiceMode.Speaking
        _state.update {
            it.copy(
                mode = FridayVoiceMode.Speaking,
                status = "Speaking",
                transcript = "",
                amplitude = 0.6f,
            )
        }
        val utteranceId = UUID.randomUUID().toString()
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        } else {
            scope.launch {
                delay(500)
                if (_state.value.isEnabled) startWakeWordListening()
            }
        }
    }

    fun destroy() {
        stop()
        recognizer.destroy()
        tts.shutdown()
        scope.cancel()
    }

    private fun startWakeWordListening() {
        if (!_state.value.isEnabled) return
        currentMode = FridayVoiceMode.WaitingForWakeWord
        _state.update {
            it.copy(
                mode = FridayVoiceMode.WaitingForWakeWord,
                status = "Waiting for: hi Friday",
                transcript = "",
                amplitude = 0.15f,
            )
        }
        startRecognizer()
    }

    private fun startCommandListening() {
        if (!_state.value.isEnabled) return
        currentMode = FridayVoiceMode.ListeningForCommand
        _state.update {
            it.copy(
                mode = FridayVoiceMode.ListeningForCommand,
                status = "Listening",
                transcript = "",
                amplitude = 0.4f,
            )
        }
        startRecognizer()
    }

    private fun startRecognizer() {
        recognizer.cancel()
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            },
        )
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() {
        _state.update { it.copy(status = if (currentMode == FridayVoiceMode.ListeningForCommand) "Listening" else it.status) }
    }

    override fun onRmsChanged(rmsdB: Float) {
        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        _state.update { it.copy(amplitude = normalized) }
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        if (!_state.value.isEnabled) return
        restartWakeWordSoon()
    }

    override fun onResults(results: Bundle?) {
        val best = results.bestSpeechText()
        handleSpeech(best)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults.bestSpeechText()
        if (partial.isNotBlank()) {
            _state.update { it.copy(transcript = partial) }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun handleSpeech(text: String) {
        if (text.isBlank()) {
            restartWakeWordSoon()
            return
        }

        _state.update { it.copy(transcript = text) }
        when (currentMode) {
            FridayVoiceMode.WaitingForWakeWord -> {
                val command = extractCommandAfterWakeWord(text)
                when {
                    command == null -> restartWakeWordSoon()
                    command.isBlank() -> startCommandListening()
                    else -> onCommand(command)
                }
            }

            FridayVoiceMode.ListeningForCommand -> onCommand(text)
            else -> Unit
        }
    }

    private fun restartWakeWordSoon() {
        scope.launch {
            delay(RESTART_DELAY_MS)
            if (_state.value.isEnabled) startWakeWordListening()
        }
    }

    private fun Bundle?.bestSpeechText(): String =
        this
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .trim()

    private fun extractCommandAfterWakeWord(text: String): String? {
        val lower = text.lowercase(Locale.US)
        val wakeWords = listOf("hi friday", "hey friday", "hello friday")
        val matched = wakeWords.firstOrNull { lower.contains(it) } ?: return null
        val start = lower.indexOf(matched) + matched.length
        return text.drop(start).trimStart(',', ' ', '.', ':')
    }

    companion object {
        private const val RESTART_DELAY_MS = 650L
    }
}
