package com.example.aiapp.agenttask.infrastructure.events;

import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AgentEventPublisher {

    private final AgentTaskRepository repository;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public AgentEventPublisher(AgentTaskRepository repository) {
        this.repository = repository;
    }

    public long publish(long taskId, String eventType, Map<String, Object> payload) {
        long sequence = repository.appendEvent(taskId, eventType, payload);
        send(taskId, sequence, eventType, payload);
        return sequence;
    }

    public SseEmitter subscribe(long taskId, long afterSequence) {
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(ignored -> remove(taskId, emitter));
        for (AgentTaskRepository.EventRecord event : repository.listEvents(taskId, afterSequence, 1000)) {
            sendOne(emitter, taskId, event.sequence(), event.eventType(), event.payload());
        }
        return emitter;
    }

    @Scheduled(fixedDelayString = "${app.agent-task.sse-heartbeat-millis:15000}")
    public void heartbeat() {
        emitters.forEach((taskId, subscribers) -> subscribers.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("taskId", taskId)));
            } catch (IOException exc) {
                remove(taskId, emitter);
            }
        }));
    }

    private void send(long taskId, long sequence, String eventType, Map<String, Object> payload) {
        List<SseEmitter> subscribers = emitters.get(taskId);
        if (subscribers == null) return;
        subscribers.forEach(emitter -> sendOne(emitter, taskId, sequence, eventType, payload));
    }

    private void sendOne(
            SseEmitter emitter,
            long taskId,
            long sequence,
            String eventType,
            Map<String, Object> payload
    ) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(sequence))
                    .name(eventType)
                    .data(Map.of(
                            "sequence", sequence,
                            "eventType", eventType,
                            "payload", payload,
                            "occurredAt", java.time.OffsetDateTime.now(ZoneOffset.UTC).toString()
                    )));
        } catch (IOException exc) {
            // An I/O failure means the client has already disconnected. Avoid
            // a redundant completion dispatch, which Spring may log as Broken pipe.
            remove(taskId, emitter);
        }
    }

    private void remove(long taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> subscribers = emitters.get(taskId);
        if (subscribers == null) return;
        subscribers.remove(emitter);
        if (subscribers.isEmpty()) emitters.remove(taskId, subscribers);
    }
}
