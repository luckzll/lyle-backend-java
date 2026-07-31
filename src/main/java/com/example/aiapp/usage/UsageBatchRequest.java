package com.example.aiapp.usage;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UsageBatchRequest(
        @NotEmpty
        @Size(max = 200)
        List<UsageEventRequest> events
) {
}
