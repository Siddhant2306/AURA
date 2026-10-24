package com.example.phone_agent.agent.statemachine

enum class AgentStatus {
    IDLE,
    PLANNING,
    EXECUTING,
    WAITING_FOR_RESULT,
    WAITING_FOR_UI_SETTLE,
    OBSERVING,
    RECOVERING,
    CHECKING_PROGRESS,
    COMPLETED,
    FAILED
}
