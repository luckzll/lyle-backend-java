package com.example.aiapp.agenttask.domain;

public enum AgentStepStatus {
    PENDING,
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED,
    WAITING_CONFIRMATION,
    WAITING_INPUT,
    CANCELLED
}
