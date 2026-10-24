package com.example.phone_agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.math.pow

class PhoneControlService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        configureServiceInfo()
        Log.i(TAG, "Phone control accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPackageName = event.packageName?.toString()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Phone control accessibility service interrupted")
    }

    override fun onDestroy() {
        // A replaced service can finish destruction after its replacement connects.
        if (instance === this) {
            instance = null
            currentPackageName = null
        }
        super.onDestroy()
    }

    suspend fun openApp(packageName: String): String = withContext(Dispatchers.Main) {
        require(packageName.isNotBlank()) { "package_name must not be blank" }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        try {
            startActivity(launchIntent)
            "Opened app package $packageName"
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No launchable activity found for $packageName", e)
        }
    }

    suspend fun getUiTree(): String = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: throw IllegalStateException("No active window is available")
        buildJsonObject {
            (root.packageName?.toString() ?: currentPackageName)?.let { put("package", it) }
            put("tree", serializeNode(root, depth = 0))
        }.toString()
    }

    suspend fun tap(x: Float, y: Float): String {
        require(x >= 0 && y >= 0) { "tap coordinates must be non-negative" }
        val path = Path().apply { moveTo(x, y) }
        check(performGesture(path, TAP_DURATION_MS)) { "Tap gesture was cancelled or rejected" }
        return "Tapped at (${x.toInt()}, ${y.toInt()})"
    }

    suspend fun tapByText(query: String): String = withContext(Dispatchers.Main) {
        require(query.isNotBlank()) { "query must not be blank" }
        val root = rootInActiveWindow ?: throw IllegalStateException("No active window is available")
        val candidates = mutableListOf<NodeCandidate>()
        collectCandidates(root, candidates)
        val target = candidates.mapNotNull { candidate ->
            if (!candidate.node.isVisibleToUser || !candidate.node.isEnabled) return@mapNotNull null
            val score = TextTargetRanking.score(query, listOf(clean(candidate.text), clean(candidate.description), clean(candidate.hint)),
                candidate.editable, candidate.node.isClickable) ?: return@mapNotNull null
            nearestClickableAncestor(candidate.node)?.let { score to candidate }
        }.maxByOrNull { it.first }?.second
            ?: throw IllegalStateException("No actionable node text, hint or description matches '$query'")
        if (target.editable) {
            // Chrome virtual text fields may acknowledge ACTION_CLICK without activating input.
            // A gesture targets the observed field itself; the agent still verifies fresh focus afterward.
            check(target.bounds.width() > 0 && target.bounds.height() > 0) { "Matched input has no visible bounds" }
            val path = Path().apply { moveTo(target.bounds.exactCenterX(), target.bounds.exactCenterY()) }
            check(performGesture(path, TAP_DURATION_MS)) { "Input tap gesture failed for '$query'" }
        } else {
            check(nearestClickableAncestor(target.node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                "Click action failed for '$query'"
            }
        }
        "Tapped best exact/actionable match for '$query'"
    }

    suspend fun typeText(text: String): String = withContext(Dispatchers.Main) {
        val focusedNode = findFocusedEditableNode()
            ?: throw IllegalStateException("No editable field is currently focused")
        val currentText = focusedNode.text?.toString().orEmpty()
        setText(focusedNode, currentText + text)
        "Typed ${text.length} characters into the focused field"
    }

    suspend fun typeIntoFieldNear(query: String, text: String): String = withContext(Dispatchers.Main) {
        require(query.isNotBlank()) { "query must not be blank" }
        val root = rootInActiveWindow ?: throw IllegalStateException("No active window is available")
        val candidates = mutableListOf<NodeCandidate>()
        collectCandidates(root, candidates)

        val target = candidates.filter { it.editable && it.node.isVisibleToUser && it.node.isEnabled }
            .mapNotNull { candidate -> TextTargetRanking.score(query, listOf(candidate.text, candidate.description, candidate.hint), true, candidate.node.isClickable)?.let { it to candidate } }
            .maxByOrNull { it.first }?.second
            ?: nearestEditableToLabel(candidates, query)
            ?: throw IllegalStateException("No editable field found near '$query'")

        check(target.bounds.width() > 0 && target.bounds.height() > 0) { "Matched input has no visible bounds" }
        val path = Path().apply { moveTo(target.bounds.exactCenterX(), target.bounds.exactCenterY()) }
        check(performGesture(path, TAP_DURATION_MS)) { "Input activation gesture failed" }
        delay(150)
        val focused = findFocusedEditableNode() ?: throw IllegalStateException("Matched input did not become focused")
        focused.refresh()
        setText(focused, text)
        "Typed ${text.length} characters into the field near '$query'"
    }

    suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): String {
        require(x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) {
            "swipe coordinates must be non-negative"
        }
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        check(performGesture(path, SWIPE_DURATION_MS)) { "Swipe gesture was cancelled or rejected" }
        return "Swiped from (${x1.toInt()}, ${y1.toInt()}) to (${x2.toInt()}, ${y2.toInt()})"
    }

    suspend fun goHome(): String = withContext(Dispatchers.Main) {
        check(performGlobalAction(GLOBAL_ACTION_HOME)) { "Home action failed" }
        "Sent HOME"
    }

    suspend fun goBack(): String = withContext(Dispatchers.Main) {
        check(performGlobalAction(GLOBAL_ACTION_BACK)) { "Back action failed" }
        "Sent BACK"
    }

    suspend fun pressEnter(): String = withContext(Dispatchers.Main) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "IME submit requires Android 11 or later. Inspect the UI and tap its visible search/submit control instead."
        }
        val focusedNode = findFocusedEditableNode()
            ?: throw IllegalStateException("No editable field is focused to submit")
        val submitted = focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        check(submitted) { "Could not submit the focused field" }
        "Pressed enter/submit on the focused field"
    }

    private fun configureServiceInfo() {
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                inputMethodEditorFlag()
            notificationTimeout = NOTIFICATION_TIMEOUT_MS
        }
    }

    private suspend fun performGesture(path: Path, durationMs: Long): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    private fun serializeNode(node: AccessibilityNodeInfo, depth: Int): JsonObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val children = if (depth < MAX_UI_TREE_DEPTH) serializeChildren(node, depth) else JsonArray(emptyList())

        return buildJsonObject {
            clean(node.text?.toString())?.let { put("text", it) }
            clean(node.contentDescription?.toString())?.let { put("desc", it) }
            clean(node.className?.toString())?.let { put("class", it.substringAfterLast('.')) }
            put("bounds", buildJsonArray {
                add(bounds.left)
                add(bounds.top)
                add(bounds.right)
                add(bounds.bottom)
            })
            if (node.isClickable) put("clickable", true)
            if (node.isEditable) put("editable", true)
            if (node.isFocused) put("focused", true)
            if (children.isNotEmpty()) put("children", children)
        }
    }

    private fun serializeChildren(node: AccessibilityNodeInfo, depth: Int): JsonArray = buildJsonArray {
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                add(serializeNode(child, depth + 1))
            }
        }
    }

    private fun findFirstNodeContaining(
        node: AccessibilityNodeInfo,
        query: String,
    ): AccessibilityNodeInfo? {
        if (nodeContains(node, query)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findFirstNodeContaining(child, query)
            if (match != null) return match
        }
        return null
    }

    private fun nearestClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true) return focused
        val root = rootInActiveWindow ?: return null
        return findFirstEditable(root)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findFirstEditable(child)
            if (match != null) return match
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            "Text input action failed"
        }
    }

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        out: MutableList<NodeCandidate>,
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        out += NodeCandidate(
            node = node,
            bounds = bounds,
            text = node.text?.toString(),
            description = node.contentDescription?.toString(),
            hint = node.hintTextCompat(),
            editable = node.isEditable,
        )
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectCandidates(it, out) }
        }
    }

    private fun nearestEditableToLabel(
        candidates: List<NodeCandidate>,
        query: String,
    ): NodeCandidate? {
        val label = candidates.firstOrNull { !it.editable && it.contains(query) } ?: return null
        return candidates
            .filter { it.editable }
            .minByOrNull { editable ->
                label.bounds.centerDistanceTo(editable.bounds) +
                    if (editable.bounds.top >= label.bounds.top) 0.0 else ABOVE_LABEL_PENALTY
            }
    }

    private fun nodeContains(node: AccessibilityNodeInfo, query: String): Boolean =
        clean(node.text?.toString())?.contains(query, ignoreCase = true) == true ||
            clean(node.contentDescription?.toString())?.contains(query, ignoreCase = true) == true ||
            clean(node.hintTextCompat())?.contains(query, ignoreCase = true) == true

    private fun AccessibilityNodeInfo.hintTextCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) hintText?.toString() else null

    private fun Rect.centerDistanceTo(other: Rect): Double {
        val dx = centerX() - other.centerX()
        val dy = centerY() - other.centerY()
        return dx.toDouble().pow(2.0) + dy.toDouble().pow(2.0)
    }

    private fun inputMethodEditorFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
        } else {
            0
        }

    private fun clean(value: String?): String? =
        value
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.replace('\t', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > MAX_TEXT_LENGTH) it.take(MAX_TEXT_LENGTH) + "..." else it }

    private data class NodeCandidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val text: String?,
        val description: String?,
        val hint: String?,
        val editable: Boolean,
    ) {
        fun contains(query: String): Boolean =
            text?.contains(query, ignoreCase = true) == true ||
                description?.contains(query, ignoreCase = true) == true ||
                hint?.contains(query, ignoreCase = true) == true
    }

    companion object {
        private const val TAG = "PhoneControlService"
        private const val NOTIFICATION_TIMEOUT_MS = 100L
        private const val TAP_DURATION_MS = 60L
        private const val SWIPE_DURATION_MS = 350L
        private const val MAX_UI_TREE_DEPTH = 60
        private const val MAX_TEXT_LENGTH = 160
        private const val ABOVE_LABEL_PENALTY = 1_000_000.0

        @Volatile
        var instance: PhoneControlService? = null
            private set

        @Volatile
        private var currentPackageName: String? = null
    }
}
