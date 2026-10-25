package com.example.phone_agent.agent.core

/** Technical payloads stay out of UI/TTS; explanations are deterministic and do not need an LLM. */
internal object FailureMessage {
    fun explain(reason: String): String {
        val r = reason.lowercase()
        return when {
            "screen changed before action" in r -> "The foreground app changed before the action, so I stopped that action and needed a fresh screen observation."
            "401" in r || "unauthorized" in r || "bearer token" in r -> "The app could not authenticate with the phone tool server. Check that the saved token matches the running server."
            "no launchable activity" in r -> "The requested app has no launcher visible to Friday in this profile. Check its installation, enabled state and app visibility."
            "securityexception" in r || "permission denial" in r || "permission denied" in r -> "Android denied the action. Check the app's permissions and user profile."
            "could not obtain" in r || "malformed ui" in r -> "I could not read a usable screen after the action."
            "accessibility" in r -> "The phone accessibility service is unavailable. Reconnect Phone Control in Accessibility settings."
            "ambiguous" in r -> "More than one control matched, so I could not safely select the intended control."
            "focused" in r || "text input" in r || "input did not" in r -> "I could not confirm the intended text field was ready for input."
            "coordinates" in r || "swipe distance" in r -> "The gesture did not have valid coordinates for the current screen."
            "json" in r || "schema" in r || "model response" in r -> "The model did not return a valid action after the allowed retry."
            "timeout" in r || "time limit" in r -> "The task timed out before I could verify the remaining step."
            "repeated" in r || "limit" in r || "budget" in r -> "I stopped because the remaining step was not making verified progress."
            "connect" in r || "http" in r || "socket" in r -> "I could not communicate with the model or phone tool server. Check their connections."
            else -> if (reason.length <= 200 && !Regex("[{}\\[\\]\\r\\n]|https?://|stacktrace|exception", RegexOption.IGNORE_CASE).containsMatchIn(reason)) reason
                else "The action failed before I could confirm its result. Technical details are in the app logs."
        }
    }
}
