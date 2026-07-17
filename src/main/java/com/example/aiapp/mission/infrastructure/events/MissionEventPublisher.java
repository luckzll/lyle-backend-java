package com.example.aiapp.mission.infrastructure.events;

import com.example.aiapp.mission.infrastructure.MissionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class MissionEventPublisher {

    private static final int MAX_SUMMARY_LENGTH = 500;

    private final MissionRepository repository;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public MissionEventPublisher(MissionRepository repository) {
        this.repository = repository;
    }

    public long publish(long missionId, String eventType, Map<String, Object> rawPayload) {
        Map<String, Object> payload = sanitize(rawPayload);
        String status = text(payload.get("status"));
        long sequence = repository.appendMissionEvent(
                missionId, eventType, status.isBlank() ? null : status, payload);
        send(missionId, sequence, eventType, status, payload);
        return sequence;
    }

    public SseEmitter subscribe(long missionId, long afterSequence) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        emitters.computeIfAbsent(missionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(missionId, emitter));
        emitter.onTimeout(() -> remove(missionId, emitter));
        emitter.onError(ignored -> remove(missionId, emitter));
        for (MissionRepository.MissionEventRecord event
                : repository.listMissionEvents(missionId, afterSequence, 1000)) {
            sendOne(
                    emitter,
                    missionId,
                    event.sequence(),
                    event.eventType(),
                    event.status(),
                    event.payload(),
                    event.createdAt().atOffset(ZoneOffset.UTC).toString()
            );
        }
        return emitter;
    }

    @Scheduled(fixedDelayString = "${app.mission.sse-heartbeat-millis:15000}")
    public void heartbeat() {
        emitters.forEach((missionId, subscribers) -> subscribers.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(Map.of("missionId", String.valueOf(missionId))));
            } catch (IOException exc) {
                remove(missionId, emitter);
            }
        }));
    }

    private void send(
            long missionId,
            long sequence,
            String eventType,
            String status,
            Map<String, Object> payload
    ) {
        List<SseEmitter> subscribers = emitters.get(missionId);
        if (subscribers == null) return;
        String occurredAt = java.time.OffsetDateTime.now(ZoneOffset.UTC).toString();
        subscribers.forEach(emitter -> sendOne(
                emitter, missionId, sequence, eventType, status, payload, occurredAt));
    }

    private void sendOne(
            SseEmitter emitter,
            long missionId,
            long sequence,
            String eventType,
            String status,
            Map<String, Object> payload,
            String occurredAt
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sequence", sequence);
        data.put("eventType", eventType);
        data.put("missionId", String.valueOf(missionId));
        data.put("status", status == null || status.isBlank() ? "UNKNOWN" : status);
        data.put("count", number(payload.get("count"), 1));
        data.put("summary", safeSummary(text(payload.get("summary"))));
        data.put("payload", payload);
        data.put("occurredAt", occurredAt);
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(sequence))
                    .name(eventType)
                    .data(data));
        } catch (IOException exc) {
            // The servlet container owns disconnect completion after an I/O
            // failure. Completing the emitter again can trigger a second async
            // dispatch and turn a normal browser disconnect into an ERROR log.
            remove(missionId, emitter);
        }
    }

    private void remove(long missionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> subscribers = emitters.get(missionId);
        if (subscribers == null) return;
        subscribers.remove(emitter);
        if (subscribers.isEmpty()) emitters.remove(missionId, subscribers);
    }

    private static Map<String, Object> sanitize(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) return result;
        source.forEach((key, value) -> {
            String normalized = key.toLowerCase();
            if (normalized.contains("secret")
                    || normalized.contains("token")
                    || normalized.contains("password")
                    || normalized.contains("authorization")) {
                result.put(key, "[REDACTED]");
            } else if ("summary".equalsIgnoreCase(key)) {
                result.put(key, safeSummary(text(value)));
            } else {
                result.put(key, sanitizeValue(value));
            }
        });
        return result;
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), item));
            return sanitize(converted);
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>();
            list.forEach(item -> converted.add(sanitizeValue(item)));
            return converted;
        }
        return value;
    }

    private static String safeSummary(String value) {
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() > MAX_SUMMARY_LENGTH
                ? compact.substring(0, MAX_SUMMARY_LENGTH) + "…"
                : compact;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
