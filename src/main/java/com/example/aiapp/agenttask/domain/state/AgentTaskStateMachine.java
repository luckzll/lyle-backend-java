package com.example.aiapp.agenttask.domain.state;

import com.example.aiapp.agenttask.domain.AgentTaskStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class AgentTaskStateMachine {

    private static final Map<AgentTaskStatus, Set<AgentTaskStatus>> TRANSITIONS = transitions();

    public void requireTransition(AgentTaskStatus from, AgentTaskStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("非法任务状态转换：" + from + " -> " + to);
        }
    }

    public boolean canTransition(AgentTaskStatus from, AgentTaskStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    private static Map<AgentTaskStatus, Set<AgentTaskStatus>> transitions() {
        Map<AgentTaskStatus, Set<AgentTaskStatus>> result = new EnumMap<>(AgentTaskStatus.class);
        result.put(AgentTaskStatus.DRAFT, EnumSet.of(AgentTaskStatus.PLANNING, AgentTaskStatus.CANCELLED));
        result.put(AgentTaskStatus.PLANNING, EnumSet.of(AgentTaskStatus.DRAFT, AgentTaskStatus.AWAITING_PLAN_APPROVAL, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED));
        result.put(AgentTaskStatus.AWAITING_PLAN_APPROVAL, EnumSet.of(AgentTaskStatus.PLANNING, AgentTaskStatus.READY, AgentTaskStatus.CANCELLED));
        result.put(AgentTaskStatus.READY, EnumSet.of(AgentTaskStatus.RUNNING, AgentTaskStatus.CANCELLED));
        result.put(AgentTaskStatus.RUNNING, EnumSet.of(
                AgentTaskStatus.AWAITING_CONFIRMATION,
                AgentTaskStatus.AWAITING_INPUT,
                AgentTaskStatus.PAUSING,
                AgentTaskStatus.COMPLETED,
                AgentTaskStatus.PARTIALLY_COMPLETED,
                AgentTaskStatus.FAILED,
                AgentTaskStatus.CANCELLED
        ));
        result.put(AgentTaskStatus.AWAITING_CONFIRMATION, EnumSet.of(AgentTaskStatus.RUNNING, AgentTaskStatus.AWAITING_INPUT, AgentTaskStatus.PARTIALLY_COMPLETED, AgentTaskStatus.CANCELLED));
        result.put(AgentTaskStatus.AWAITING_INPUT, EnumSet.of(AgentTaskStatus.RUNNING, AgentTaskStatus.AWAITING_PLAN_APPROVAL, AgentTaskStatus.PARTIALLY_COMPLETED, AgentTaskStatus.CANCELLED));
        result.put(AgentTaskStatus.PAUSING, EnumSet.of(AgentTaskStatus.PAUSED, AgentTaskStatus.CANCELLED));
        result.put(AgentTaskStatus.PAUSED, EnumSet.of(AgentTaskStatus.RUNNING, AgentTaskStatus.CANCELLED));
        return Map.copyOf(result);
    }
}
