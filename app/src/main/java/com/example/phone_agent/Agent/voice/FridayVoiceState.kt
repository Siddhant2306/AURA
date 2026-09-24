package com.example.phone_agent.agent.voice

enum class FridayVoiceMode {
    Stopped,
    WaitingForWakeWord,
    ListeningForCommand,
    Thinking,
    Speaking,
    Error,
}

data class FridayVoiceState(
    val mode: FridayVoiceMode = FridayVoiceMode.Stopped,
    val status: String = "Say hi Friday to begin.",
    val transcript: String = "",
    val amplitude: Float = 0f,
    val isEnabled: Boolean = false,
)
