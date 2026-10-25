package com.example.phone_agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.UiAutomation
import android.provider.Settings
import java.io.FileInputStream
import com.example.phone_agent.service.PhoneControlService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.BeforeClass
import org.junit.runner.RunWith

/** Real service/PackageManager launches; no shell launch substitutes or model mocks. */
@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentedTest {
    companion object {
        @JvmStatic @BeforeClass fun reconnectEnabledServiceAfterInstrumentationRestart() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val original = Settings.Secure.getString(instrumentation.targetContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val own = "com.example.phone_agent/com.example.phone_agent.service.PhoneControlService"
            val services = original.split(':').filter { it.isNotBlank() }
            if (services.none { it == own || it == "com.example.phone_agent/.service.PhoneControlService" }) return
            fun shell(command: String) { automation.executeShellCommand(command).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } } }
            val others = services.filter { it != own && it != "com.example.phone_agent/.service.PhoneControlService" }
            try {
                if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
                else shell("settings put secure enabled_accessibility_services ${others.joinToString(":")}")
                Thread.sleep(300)
            } finally { shell("settings put secure enabled_accessibility_services $original") }
        }
    }
    private fun launchAndObserve(query: String) = runBlocking {
        repeat(30) { if (PhoneControlService.instance == null) delay(100) }
        val service = PhoneControlService.instance
        assumeTrue("Enable Phone Control accessibility before device validation", service != null)
        val apps = Json.parseToJsonElement(service!!.findApp(query)).jsonObject.getValue("apps").jsonArray
        assumeTrue("$query is not installed/launchable in this profile", apps.isNotEmpty())
        val target = apps.first().jsonObject.getValue("package_name").jsonPrimitive.content
        repeat(2) {
            var foreground: String? = null
            var stable = false
            // Match the production contract: an observed failure permits one bounded recovery.
            // Require sustained foreground so another app's delayed login redirect is not mistaken for readiness.
            for (launchAttempt in 0 until 2) {
                service.openApp(target)
                val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
                var stableSince: Long? = null
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(150)
                    foreground = Json.parseToJsonElement(service.getUiTree()).jsonObject["package"]?.jsonPrimitive?.content
                    if (foreground != target) stableSince = null
                    else {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (stableSince == null) stableSince = now
                        if (now - stableSince >= 1_500) { stable = true; break }
                    }
                }
                if (stable) break
            }
            assertEquals("Launch receipt did not establish foreground app", target, foreground)
            assertTrue("Foreground did not remain stable", stable)
        }
    }
    @Test fun whatsappLaunchesThroughFridayService() = launchAndObserve("WhatsApp")
    @Test fun instagramLaunchesThroughFridayService() = launchAndObserve("Instagram")
    @Test fun facebookLaunchesThroughFridayService() = launchAndObserve("Facebook")
    @Test fun defaultPhoneAppLaunchesThroughFridayService() = launchAndObserve("Phone")
    @Test fun spotifyLaunchesThroughFridayService() = launchAndObserve("Spotify")
    private fun nodes(raw: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        fun visit(node: JsonObject) { result += node; (node["children"] as? JsonArray)?.forEach { visit(it.jsonObject) } }
        visit(Json.parseToJsonElement(raw).jsonObject.getValue("tree").jsonObject)
        return result
    }
    private fun labels(node: JsonObject) = listOf("text", "hint", "desc").mapNotNull { node[it]?.jsonPrimitive?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
    private fun box(node: JsonObject) = node.getValue("bounds").jsonArray.map { it.jsonPrimitive.int }
    @Test fun spotifySearchNavigationAndTextAreVerifiedTwice() = runBlocking {
        launchAndObserve("Spotify")
        val service = requireNotNull(PhoneControlService.instance)
        repeat(2) {
            var current = service.getUiTree()
            val searchTab = nodes(current).filter { node -> node["visible"]?.toString() != "false" && labels(node).any { it.equals("Search", true) } }
                .maxByOrNull { box(it)[1] }
            if (searchTab != null) { service.tapByText("Search", targetBounds = box(searchTab), preferGesture = true); delay(300) }
            var input: JsonObject? = null
            for (attempt in 0 until 4) {
                current = service.getUiTree()
                val editable = nodes(current).filter { node -> node["editable"]?.toString() == "true" && node["visible"]?.toString() != "false" }
                input = editable.firstOrNull { node -> labels(node).any { Regex("search|listen|yesterday", RegexOption.IGNORE_CASE).containsMatchIn(it) } } ?: editable.singleOrNull()
                if (input != null) break
                val entry = nodes(current).filter { node -> node["visible"]?.toString() != "false" && labels(node).any { label -> label.length < 100 && Regex("search|listen", RegexOption.IGNORE_CASE).containsMatchIn(label) } }
                    .filter { node -> labels(node).any { it.contains("listen", true) || it.contains("search for", true) } }
                    .minByOrNull { box(it)[1] } ?: break
                service.tapByText(labels(entry).first(), targetBounds = box(entry), preferGesture = true)
                delay(300)
            }
            assertNotNull("Spotify search did not expose a usable editable field (check login/overlays)", input)
            val field = input!!
            val fieldBounds = box(field)
            val fieldLabel = labels(field).firstOrNull() ?: nodes(current).filter { child ->
                val bounds = box(child)
                bounds[0] >= fieldBounds[0] && bounds[1] >= fieldBounds[1] && bounds[2] <= fieldBounds[2] && bounds[3] <= fieldBounds[3]
            }.flatMap(::labels).firstOrNull { it.contains("listen", true) || it.contains("search", true) }
            assertNotNull("Search field has no observed identifying label", fieldLabel)
            service.typeIntoFieldNear(fieldLabel!!, "Yesterday")
            val typed = nodes(service.getUiTree())
            assertTrue("Text was not observed in the focused search field", typed.any { it["editable"]?.toString() == "true" && it["focused"]?.toString() == "true" && it["text"]?.jsonPrimitive?.content == "Yesterday" })
            service.pressEnter()
            var resultsVisible = false
            for (attempt in 0 until 12) {
                delay(200)
                if (nodes(service.getUiTree()).any { it["editable"]?.toString() != "true" && labels(it).any { label -> label.contains("Yesterday", true) } }) { resultsVisible = true; break }
            }
            assertTrue("Submitted query did not expose actual non-editable search results", resultsVisible)
        }
    }
    @Test fun leftAndRightGesturesExecuteAndAreObserved() = runBlocking {
        launchAndObserve("Spotify")
        val service = requireNotNull(PhoneControlService.instance)
        val initial = Json.parseToJsonElement(service.getUiTree()).jsonObject
        val screen = initial.getValue("screen").jsonObject
        val width = screen.getValue("width").jsonPrimitive.int.toFloat()
        val height = screen.getValue("height").jsonPrimitive.int.toFloat()
        val rejected = com.example.phone_agent.mcp.ToolRegistry.call("tap", buildJsonObject { put("x", 1); put("y", 1); put("expected_package", "com.example.wrong_foreground") })
        assertTrue("Stale foreground action was not rejected", rejected.isError)
        service.swipe(width * .8f, height * .55f, width * .2f, height * .55f)
        assertEquals("com.spotify.music", Json.parseToJsonElement(service.getUiTree()).jsonObject.getValue("package").jsonPrimitive.content)
        service.swipe(width * .2f, height * .55f, width * .8f, height * .55f)
        assertEquals("com.spotify.music", Json.parseToJsonElement(service.getUiTree()).jsonObject.getValue("package").jsonPrimitive.content)
    }
}
