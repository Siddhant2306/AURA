package com.example.phone_agent.agent.core

import com.example.phone_agent.agent.model.*
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

/** Parses the registry's {package, tree:{text,desc,editable,focused,children:[...]}} output. */
internal class UiObservation private constructor(val root: JsonObject, val nodes: List<JsonObject>) {
    val packageName: String? get() = (root["package"] as? JsonPrimitive)?.contentOrNull
    val visibleText: String get() = nodes.flatMap { node -> listOfNotNull(
        (node["text"] as? JsonPrimitive)?.contentOrNull,
        (node["desc"] as? JsonPrimitive)?.contentOrNull,
    ) }.joinToString(" ")
    val truncated: Boolean get() = (root["truncated"] as? JsonPrimitive)?.booleanOrNull == true
    /** Bounded, relevance-ranked pages; every omitted entry remains available from the cached full tree. */
    fun decisionTree(context: String = "", page: Int = 0, budget: Int = 7_000): JsonObject {
        fun meaningful(node: JsonObject) = listOf("text", "desc", "hint").any {
            (node[it] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        } || listOf("editable", "clickable", "focused").any { (node[it] as? JsonPrimitive)?.booleanOrNull == true }
        if (root.toString().length <= budget && (nodes.size == 1 || page > 0)) return root
        val words = normalize(context).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in setOf("the", "and", "field", "current", "goal", "show", "results", "appear") }.toSet()
        val entries = nodes.flatMapIndexed { index, node ->
            val plain = node.filterKeys { it != "children" }
            val long = plain.filter { it.key in setOf("text", "desc", "hint") && (it.value as? JsonPrimitive)?.contentOrNull.orEmpty().length > 2_000 }
            val base = plain - long.keys
            val pieces = if (long.isEmpty()) listOf(JsonObject(plain)) else long.flatMap { (key, value) ->
                value.jsonPrimitive.content.chunked(2_000).map { JsonObject(base + (key to JsonPrimitive(it))) }
            }
            pieces.map { entry ->
                val text = normalize(listOf("text", "desc", "hint").joinToString(" ") { (entry[it] as? JsonPrimitive)?.contentOrNull.orEmpty() })
                val score = (if (entry["editable"]?.toString() == "true") 1_000 else 0) +
                    (if (entry["focused"]?.toString() == "true" && entry["editable"]?.toString() == "true") 1_000
                     else if (entry["focused"]?.toString() == "true" && text.length < 300) 300 else 0) +
                    (if (entry["clickable"]?.toString() == "true") 150 else 0) +
                    words.count { text.contains(it) } * 100 +
                    (if (node["children"] == null) 20 else -50)
                Triple(index, score, entry)
            }
        }
        val semantic = entries.filter { meaningful(it.third) }
        val flat = JsonObject(root + ("tree" to buildJsonObject { put("children", JsonArray(semantic.map { it.third })) }))
        if (flat.toString().length <= budget && page == 0) return flat
        val pages = mutableListOf<MutableList<Triple<Int, Int, JsonObject>>>()
        var used = 0
        entries.sortedByDescending { it.second }.forEach { entry ->
            val size = entry.third.toString().length + 1
            if (pages.isEmpty() || used + size > budget - 300) { pages += mutableListOf<Triple<Int, Int, JsonObject>>(); used = 0 }
            pages.last().add(entry); used += size
        }
        val selected = pages.getOrElse(page.coerceAtLeast(0)) { pages.last() }.sortedBy { it.first }
        return buildJsonObject {
            root["package"]?.let { put("package", it) }
            put("tree", buildJsonObject { put("children", JsonArray(selected.map { it.third })) })
            put("truncated", true); put("page", page.coerceIn(0, pages.lastIndex)); put("page_count", pages.size)
            put("omitted_entries", entries.size - selected.size)
        }
    }
    fun matches(expected: UiExpectation): Boolean {
        if (!valid(expected)) return false
        if (expected.packageName != null && packageName?.trim() != expected.packageName.trim()) return false
        if (expected.text != null && !normalize(visibleText).contains(normalize(expected.text))) return false
        val editable = nodes.filter { (it["editable"] as? JsonPrimitive)?.booleanOrNull == true }
        val matchingEditable = if (expected.editableText == null) editable else editable.filter {
            normalize((it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()) == normalize(expected.editableText)
        }
        if (expected.editableText != null && matchingEditable.isEmpty()) return false
        if (expected.focusedEditable != null && matchingEditable.any {
            (it["focused"] as? JsonPrimitive)?.booleanOrNull == true
        } != expected.focusedEditable) return false
        return true
    }
    fun inputText(): String? {
        val editable = nodes.filter { (it["editable"] as? JsonPrimitive)?.booleanOrNull == true }
        val target = editable.firstOrNull { (it["focused"] as? JsonPrimitive)?.booleanOrNull == true }
            ?: editable.singleOrNull() ?: return null
        return (target["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
    fun tapsOnlyEditable(call: AgentToolCall): Boolean {
        val editables = nodes.filter { (it["editable"] as? JsonPrimitive)?.booleanOrNull == true }
        return when (call.name) {
            "tap_by_text" -> (call.arguments["query"] as? JsonPrimitive)?.contentOrNull?.let { query ->
                normalize(query).isNotBlank() && editables.count { target -> listOf("text", "desc", "hint").any {
                    normalize((target[it] as? JsonPrimitive)?.contentOrNull.orEmpty()).contains(normalize(query))
                } } == 1
            } == true
            "tap" -> {
                val x = (call.arguments["x"] as? JsonPrimitive)?.doubleOrNull ?: return false
                val y = (call.arguments["y"] as? JsonPrimitive)?.doubleOrNull ?: return false
                editables.count { target ->
                    val bounds = (target["bounds"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
                    bounds != null && bounds.size == 4 && bounds.all { it != null } && x >= bounds[0]!! && x < bounds[2]!! && y >= bounds[1]!! && y < bounds[3]!!
                } == 1
            }
            else -> false
        }
    }
    fun containsEvidence(evidence: String): Boolean = normalize(evidence).let { quote ->
        quote.isNotBlank() && (normalize(visibleText).contains(quote) || normalize(packageName.orEmpty()) == quote)
    }
    companion object {
        fun parse(text: String): UiObservation? = runCatching {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
            val tree = root["tree"] as? JsonObject ?: return null
            val nodes = mutableListOf<JsonObject>()
            fun visit(node: JsonObject): Boolean {
                for (key in listOf("text", "desc", "class", "package")) {
                    if (key in node && (node[key] !is JsonPrimitive || !(node[key] as JsonPrimitive).isString)) return false
                }
                for (key in listOf("editable", "focused", "clickable")) {
                    val flag = node[key] as? JsonPrimitive
                    if (key in node && (flag == null || flag.isString || flag.booleanOrNull == null)) return false
                }
                nodes += node
                val children = node["children"] ?: return true
                if (children !is JsonArray) return false
                return children.all { it is JsonObject && visit(it) }
            }
            val structurallyValid = visit(tree)
            val usable = nodes.any { node ->
                listOf("text", "desc").any { (node[it] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true } ||
                    listOf("editable", "clickable").any { (node[it] as? JsonPrimitive)?.booleanOrNull == true } ||
                    ((node["class"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true &&
                        (node["bounds"] as? JsonArray)?.let { bounds ->
                            val values = bounds.map { (it as? JsonPrimitive)?.intOrNull }
                            values.size == 4 && values.all { it != null } && values[2]!! > values[0]!! && values[3]!! > values[1]!!
                        } == true)
            }
            if (!structurallyValid || !usable || root["package"]?.let { it !is JsonPrimitive || !it.isString || it.content.isBlank() } == true) null
            else UiObservation(root, nodes)
        }.getOrNull()
        fun valid(e: UiExpectation): Boolean =
            (e.packageName != null || e.text != null || e.editableText != null || e.focusedEditable != null) &&
                e.packageName?.isBlank() != true && e.text?.isBlank() != true
        fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }
}

/** One interpretation of actual MCP outcomes, shared by the executor and state machine. */
internal fun normalizeToolResult(call: AgentToolCall, result: McpToolResult): McpToolResult {
    val rawError = (result.rawJson["isError"] as? JsonPrimitive)?.booleanOrNull
    if ((result.rawJson["isError"] as? JsonPrimitive)?.isString == true) return result.copy(isError = true, text = "Malformed isError flag")
    if (result.isError || rawError == true) return result.copy(isError = true)
    if (result.rawJson["isError"] != null && rawError == null) return result.copy(isError = true, text = "Malformed isError flag")
    val structured = result.rawJson["structuredContent"] as? JsonObject
    val text = if (call.name == "get_ui_tree" && structured != null) structured.toString() else result.text
    // MCPClient's fallback text for a missing content payload is not an execution receipt.
    val missingPayload = result.rawJson.isNotEmpty() && structured == null &&
        (result.rawJson["content"] as? JsonArray).isNullOrEmpty()
    return when {
        text.isBlank() || missingPayload -> result.copy(isError = true, text = "Malformed or empty tool result")
        call.name == "get_ui_tree" && UiObservation.parse(text) == null -> result.copy(isError = true, text = "Malformed UI observation: ${text.take(500)}")
        else -> result.copy(text = text)
    }
}

internal fun safeArguments(arguments: JsonObject): String = arguments.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
    val safe = key in setOf("package_name", "x", "y", "x1", "x2", "y1", "y2")
    "$key=${if (safe) value.toString() else "<redacted:${value.toString().length}>"}"
}
