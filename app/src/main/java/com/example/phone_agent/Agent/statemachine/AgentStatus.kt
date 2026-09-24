package com.example.phone_agent.Agent.statemachine

enum class AgentStatus {
    IDLE,
    PLANNING,
    EXECUTING,
    WAITING_FOR_RESULT,
    CHECKING_PROGRESS,
    COMPLETED,
    FAILED
}