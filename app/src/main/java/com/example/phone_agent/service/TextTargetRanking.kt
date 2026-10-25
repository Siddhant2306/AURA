package com.example.phone_agent.service

/** Exact control matches beat aggregate ancestor text. Kept pure for targeting regressions. */
internal object TextTargetRanking {
    fun score(query: String, values: List<String?>, editable: Boolean, clickable: Boolean): Int? {
        val candidates = values.filterNotNull().map { it.trim() }
        if (candidates.none { it.contains(query, ignoreCase = true) }) return null
        fun exact(value: String): Boolean = value.equals(query, true) ||
            (value.substringBefore(',').equals(query, true) && Regex("^\\s*(tab|button|selected|unselected)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value.substringAfter(',', "")))
        return (if (candidates.any(::exact)) 1_000 else 0) +
            (if (editable) 500 else 0) + (if (clickable) 100 else 0)
    }
}
