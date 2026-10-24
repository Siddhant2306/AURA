package com.example.phone_agent.agent

import com.example.phone_agent.service.TextTargetRanking
import org.junit.Assert.*
import org.junit.Test

class TextTargetRankingTest {
    @Test fun editableMatchBeatsBroadPageAncestorAndDuplicateHeading() {
        val ancestor = TextTargetRanking.score("today's weather", listOf("Google today's weather results and many links"), false, true)!!
        val heading = TextTargetRanking.score("today's weather", listOf("today's weather"), false, true)!!
        val input = TextTargetRanking.score("today's weather", listOf("today's weather"), true, true)!!
        assertTrue(input > heading)
        assertTrue(input > ancestor)
        assertNull(TextTargetRanking.score("Search", listOf("Unrelated"), true, true))
    }
    @Test fun exactLabelBeatsSubstringAndHintsRemainTargetable() {
        val substring = TextTargetRanking.score("Not now", listOf("Use location or select Not now"), false, true)!!
        val button = TextTargetRanking.score("Not now", listOf(null, "Not now"), false, true)!!
        assertTrue(button > substring)
        assertNotNull(TextTargetRanking.score("search", listOf(null, null, "Search Google"), true, true))
    }
}
