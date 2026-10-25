package com.example.phone_agent.service

/** Physical display coordinates; direction describes finger movement, not content movement. */
internal object GestureBounds {
    fun validate(width: Int, height: Int, x1: Float, y1: Float, x2: Float? = null, y2: Float? = null) {
        require(width > 0 && height > 0) { "Screen dimensions are unavailable" }
        fun inside(x: Float, y: Float) = x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height
        require(inside(x1, y1) && (x2 == null || y2 != null && inside(x2, y2))) { "Gesture coordinates are outside the current screen" }
        if (x2 != null && y2 != null) require(kotlin.math.abs(x2 - x1) + kotlin.math.abs(y2 - y1) >= 8) { "Swipe distance is too short" }
    }
}
