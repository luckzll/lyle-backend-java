package com.example.aiapp.agenttask.domain;

public enum AgentTaskStatus {
    DRAFT,
    PLANNING,
    AWAITING_PLAN_APPROVAL,
    READY,
    RUNNING,
    AWAITING_CONFIRMATION,
    AWAITING_INPUT,
    PAUSING,
    PAUSED,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIALLY_COMPLETED || this == FAILED || this == CANCELLED;
    }
}
