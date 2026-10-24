package com.example.phone_agent.service

/** Exact control matches beat aggregate ancestor text. Kept pure for targeting regressions. */
internal object TextTargetRanking {
    fun score(query: String, values: List<String?>, editable: Boolean, clickable: Boolean): Int? {
        val candidates = values.filterNotNull().map { it.trim() }
        if (candidates.none { it.contains(query, ignoreCase = true) }) return null
        return (if (candidates.any { it.equals(query, ignoreCase = true) }) 1_000 else 0) +
            (if (editable) 500 else 0) + (if (clickable) 100 else 0)
    }
}
