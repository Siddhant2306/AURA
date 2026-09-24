package com.example.phone_agent.agent.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ensures only one agent run is active at a time (voice + manual command paths).
 * Future multi-agent orchestrators can coordinate multiple sessions via a parent coordinator.
 */
object AgentSession {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T =
        mutex.withLock { block() }
}
