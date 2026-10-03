package com.example.aiapp.controller;

import com.example.aiapp.billing.BillingService;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.service.AiConfigService;
import com.example.aiapp.service.impl.ChatModelCatalog;
import com.example.aiapp.usage.UsageService;
import com.example.aiapp.utils.CurrentUserUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stateless relay: never inject a chat/attachment/memory mapper here. */
@RestController
@RequestMapping("/local-workspace")
public class LocalWorkspaceController {
    private static final List<String> READ_TOOLS = List.of("workspace_list", "workspace_read", "workspace_search");
    private static final List<String> AGENT_TOOLS = List.of("workspace_list", "workspace_read", "workspace_search", "workspace_edit", "workspace_create");
    private final AiServiceClient ai;
    private final AiConfigService config;
    private final UsageService usage;
    private final BillingService billing;
    private final ObjectMapper json;

    public LocalWorkspaceController(AiServiceClient ai, AiConfigService config, UsageService usage,
                                    BillingService billing, ObjectMapper json) {
        this.ai = ai; this.config = config; this.usage = usage; this.billing = billing; this.json = json;
    }

    public record Step(@NotEmpty @Size(max=100) List<Map<String, Object>> messages,
                       @Pattern(regexp="chat|agent") String mode, Boolean workspaceConnected, String modelId) {
        public Step(List<Map<String, Object>> messages) { this(messages, "agent", true, null); }
        public Step(List<Map<String, Object>> messages, String mode, Boolean workspaceConnected) {
            this(messages, mode, workspaceConnected, null);
        }
    }

    public record Document(@Size(min=1, max=128) String name,
                           @Size(min=4, max=6990508) String data) {}

    @PostMapping(value="/document/extract", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> extractDocument(@Valid @RequestBody Document request) {
        CurrentUserUtils.currentUserId();
        String name = request.name(); String data = request.data();
        if (name == null || name.length() > 128 || name.startsWith(".") || name.matches(".*[\\\\/:\\p{Cntrl}].*")
                || !name.toLowerCase(Locale.ROOT).matches(".+\\.(pdf|docx|xlsx|xlsm|xls|pptx)"))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "文档格式不支持；旧版 DOC/PPT 请转换为 DOCX/PPTX");
        if (data == null || data.isEmpty() || data.length() > 6990508)
            throw new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "文档超过 5 MB 读取限制");
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            if (decoded.length == 0 || decoded.length > 5 * 1024 * 1024 || !Base64.getEncoder().encodeToString(decoded).equals(data))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "文档数据无效");
        }
        // Parse the provided bytes without attaching them to chat, memory, RAG, or server storage.
        Map<String, Object> result = ai.extractLocalWorkspaceDocument(Map.of("name", name, "data", data));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(result);
    }

    @PostMapping(value="/step/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> step(@Valid @RequestBody Step request) {
        Long userId = CurrentUserUtils.currentUserId();
        String mode = request.mode() == null ? "agent" : request.mode();
        boolean connected = !Boolean.FALSE.equals(request.workspaceConnected());
        if (!Set.of("chat", "agent").contains(mode))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "工作区模式无效");
        Map<String, String> modelConfig = ChatModelCatalog.apply(request.modelId(), config.currentConfigMap());
        // The caller cannot expand this list through a prompt or a request-body tool_allowlist.
        List<String> allowedTools = !connected ? List.of() : mode.equals("chat") ? READ_TOOLS : AGENT_TOOLS;
        try {
            if (json.writeValueAsString(request.messages()).length() > 220_000)
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "工作区上下文已满，请开始新对话");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "工作区消息格式无效");
        }
        // Server-owned IDs prevent callers attributing usage to another user's conversation.
        String turnId = UUID.randomUUID().toString();
        StreamingResponseBody body = output -> {
            UsageService.Reservation reservation = null;
            String commercial = null;
            try {
                reservation = usage.reserve(userId, null, turnId);
                commercial = billing.reserveCommercial(userId, turnId);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("user_id", userId.toString()); payload.put("turn_id", turnId);
                payload.put("messages", request.messages()); payload.put("ai_config", modelConfig);
                payload.put("mode", mode); payload.put("workspace_connected", connected);
                payload.put("tool_allowlist", allowedTools);
                payload.put("traceId", reservation.traceId()); payload.put("reservationId", reservation.reservationId());
                payload.put("usageBudget", Map.of("maxCalls", 1L, "maxInputTokens", reservation.maxInputTokens(),
                        "maxOutputTokens", reservation.maxOutputTokens(), "maxCostMicros", reservation.maxCostMicros()));
                try (var stream = ai.streamLocalWorkspace(payload).toStream()) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        output.write(iterator.next().getBytes(StandardCharsets.UTF_8)); output.flush();
                    }
                }
            } catch (RuntimeException e) {
                // Upstream errors may contain source text. Do not log the exception or return it.
                output.write("event: error\ndata: {\"message\":\"本地项目请求失败，请检查模型连接与额度后重试。\"}\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                if (reservation != null) usage.releaseReservation(reservation.reservationId());
                if (commercial != null) billing.releaseCommercialReservation(commercial);
            }
        };
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(body);
    }
}
