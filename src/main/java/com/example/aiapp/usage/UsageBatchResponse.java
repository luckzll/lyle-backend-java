package com.example.aiapp.usage;

import java.util.List;

public record UsageBatchResponse(
        int accepted,
        int duplicate,
        int rejected,
        List<String> acceptedEventIds,
        List<String> duplicateEventIds,
        List<RejectedEvent> rejectedEvents
) {
    public record RejectedEvent(String eventId, String reason) {
    }
}
