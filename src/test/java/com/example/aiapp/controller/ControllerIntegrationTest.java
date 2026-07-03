package com.example.aiapp.controller;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.config.RateLimitProperties;
import com.example.aiapp.config.SecurityConfig;
import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.vo.AdminOverviewVO;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.domain.vo.ChatResponseVO;
import com.example.aiapp.domain.vo.ConversationVO;
import com.example.aiapp.domain.vo.FileUploadVO;
import com.example.aiapp.domain.vo.KnowledgeBaseVO;
import com.example.aiapp.domain.vo.SourceVO;
import com.example.aiapp.domain.vo.UserVO;
import com.example.aiapp.infrastructure.client.AiServiceClient;
import com.example.aiapp.mapper.AiConfigMapper;
import com.example.aiapp.mapper.AuditLogMapper;
import com.example.aiapp.mapper.ChatMessageMapper;
import com.example.aiapp.mapper.ConversationMapper;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.security.JwtAuthenticationFilter;
import com.example.aiapp.security.JwtService;
import com.example.aiapp.security.RateLimitFilter;
import com.example.aiapp.service.AdminService;
import com.example.aiapp.service.AuthService;
import com.example.aiapp.service.ChatService;
import com.example.aiapp.service.ConversationService;
import com.example.aiapp.service.FileService;
import com.example.aiapp.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuthController.class,
        ChatController.class,
        ConversationController.class,
        FileController.class,
        KnowledgeBaseController.class,
        MemoryController.class,
        RagDebugController.class,
        UserController.class,
        AdminController.class,
        AgentToolController.class
})
@Import({SecurityConfig.class, JwtService.class, JwtAuthenticationFilter.class, RateLimitFilter.class})
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class})
@TestPropertySource(properties = {
        "app.auth.jwt-secret=test-secret-key-that-is-long-enough-for-hmac",
        "app.auth.jwt-ttl-minutes=60",
        "app.rate-limit.auth-per-minute=100",
        "app.rate-limit.chat-per-minute=100"
})
class ControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtService jwtService;

    @MockBean
    AuthService authService;

    @MockBean
    ConversationService conversationService;

    @MockBean
    KnowledgeBaseService knowledgeBaseService;

    @MockBean
    FileService fileService;

    @MockBean
    ChatService chatService;

    @MockBean
    AdminService adminService;

    @MockBean
    AiServiceClient aiServiceClient;

    @MockBean
    AiConfigMapper aiConfigMapper;

    @MockBean
    AuditLogMapper auditLogMapper;

    @MockBean
    ChatMessageMapper chatMessageMapper;

    @MockBean
    ConversationMapper conversationMapper;

    @MockBean
    KnowledgeBaseMapper knowledgeBaseMapper;

    @MockBean
    KnowledgeFileMapper knowledgeFileMapper;

    @MockBean
    UserMapper userMapper;

    @Test
    void authEndpointsArePublic() throws Exception {
        when(authService.register(any(AuthRequestDTO.class))).thenReturn(new AuthResponseVO(
                new UserVO(7L, "new-user", "USER"),
                "token"
        ));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new-user","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(7))
                .andExpect(jsonPath("$.user.username").value("new-user"))
                .andExpect(jsonPath("$.token").value("token"));
    }

    @Test
    void protectedEndpointsRequireJwt() throws Exception {
        mockMvc.perform(get("/conversations"))
                .andExpect(status().isForbidden());
    }

    @Test
    void currentUserComesFromJwt() throws Exception {
        mockMvc.perform(get("/users/me")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.username").value("lyle"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void conversationControllerPassesCurrentUserToService() throws Exception {
        LocalDateTime now = LocalDateTime.parse("2026-06-30T18:00:00");
        when(conversationService.list(42L)).thenReturn(List.of(
                new ConversationVO(100L, "项目讨论", now, now)
        ));

        mockMvc.perform(get("/conversations")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].title").value("项目讨论"));

        verify(conversationService).list(42L);
    }

    @Test
    void knowledgeBaseControllerPassesCurrentUserToService() throws Exception {
        LocalDateTime now = LocalDateTime.parse("2026-06-30T18:00:00");
        when(knowledgeBaseService.list(42L)).thenReturn(List.of(
                new KnowledgeBaseVO(200L, "研发资料", now)
        ));

        mockMvc.perform(get("/knowledge-bases")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(200))
                .andExpect(jsonPath("$[0].name").value("研发资料"));

        verify(knowledgeBaseService).list(42L);
    }

    @Test
    void fileControllerRebuildsFileWithCurrentUser() throws Exception {
        when(fileService.rebuild(42L, 200L, 300L)).thenReturn(new FileUploadVO(
                300L,
                200L,
                "lyle.txt",
                "file:///storage/knowledge-files/200/lyle.txt",
                "LOCAL",
                "knowledge-files",
                "200/lyle.txt",
                "lyle.txt",
                "text/plain",
                128L,
                "private",
                "completed",
                "ingest-300",
                List.of()
        ));

        mockMvc.perform(post("/knowledge-bases/200/files/300/rebuild")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value(300))
                .andExpect(jsonPath("$.parseStatus").value("completed"))
                .andExpect(jsonPath("$.ingestTaskId").value("ingest-300"));

        verify(fileService).rebuild(42L, 200L, 300L);
    }

    @Test
    void fileControllerRebuildsWholeKnowledgeBaseWithCurrentUser() throws Exception {
        when(fileService.rebuildKnowledgeBase(42L, 200L)).thenReturn(List.of(
                new FileUploadVO(
                        300L,
                        200L,
                        "lyle.txt",
                        "file:///storage/knowledge-files/200/lyle.txt",
                        "LOCAL",
                        "knowledge-files",
                        "200/lyle.txt",
                        "lyle.txt",
                        "text/plain",
                        128L,
                        "private",
                        "completed",
                        "ingest-300",
                        List.of()
                )
        ));

        mockMvc.perform(post("/knowledge-bases/200/files/rebuild")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileId").value(300))
                .andExpect(jsonPath("$[0].ingestTaskId").value("ingest-300"));

        verify(fileService).rebuildKnowledgeBase(42L, 200L);
    }

    @Test
    void chatSendReturnsSourcesAndTrace() throws Exception {
        when(chatService.send(eq(42L), any(ChatRequestDTO.class))).thenReturn(new ChatResponseVO(
                "100",
                "答案是紫藤花。",
                List.of(new SourceVO("file-1", "lyle.txt #1", "答案是紫藤花。")),
                List.of(Map.of("agent", "rag_agent", "contextCount", 1)),
                List.of(Map.of("tool", "memory_stats", "ok", true)),
                List.of(Map.of("targetKey", "food_preference", "value", "西瓜", "status", "pending")),
                List.of(Map.of("type", "todo", "title", "完成产品定位文档", "status", "pending")),
                Map.of("approved", true)
        ));

        mockMvc.perform(post("/chat/send")
                        .header("Authorization", bearer(42L, "lyle", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"100","message":"答案是什么？","knowledgeBaseIds":["200"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("100"))
                .andExpect(jsonPath("$.answer").value("答案是紫藤花。"))
                .andExpect(jsonPath("$.sources[0].chunk").value("答案是紫藤花。"))
                .andExpect(jsonPath("$.agentTrace[0].agent").value("rag_agent"))
                .andExpect(jsonPath("$.candidateMemories[0].targetKey").value("food_preference"))
                .andExpect(jsonPath("$.candidateTasks[0].title").value("完成产品定位文档"))
                .andExpect(jsonPath("$.review.approved").value(true));

        ArgumentCaptor<ChatRequestDTO> captor = ArgumentCaptor.forClass(ChatRequestDTO.class);
        verify(chatService).send(eq(42L), captor.capture());
        assertThat(captor.getValue().knowledgeBaseIds()).containsExactly("200");
    }

    @Test
    void chatStreamForwardsSseChunks() throws Exception {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> consumer = invocation.getArgument(2, Consumer.class);
            consumer.accept("event: delta\ndata: {\"text\":\"紫藤花\"}\n\n");
            consumer.accept("event: done\ndata: {}\n\n");
            return null;
        }).when(chatService).stream(eq(42L), any(ChatRequestDTO.class), any());

        MvcResult result = mockMvc.perform(post("/chat/stream")
                        .header("Authorization", bearer(42L, "lyle", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"100","message":"答案是什么？","knowledgeBaseIds":["200"]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("紫藤花");
    }

    @Test
    void ragDebugProxiesRequestToAiService() throws Exception {
        when(knowledgeBaseMapper.selectByIdAndUserId(200L, 42L))
                .thenReturn(new com.example.aiapp.domain.entity.KnowledgeBaseEntity());
        when(aiServiceClient.debugRag(any())).thenReturn(Map.of(
                "contexts", List.of(Map.of("title", "lyle.txt #1", "chunk", "答案是紫藤花。")),
                "queries", List.of("答案是什么")
        ));

        mockMvc.perform(post("/rag/debug")
                        .header("Authorization", bearer(42L, "lyle", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"答案是什么？","knowledgeBaseIds":["200"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts[0].chunk").value("答案是紫藤花。"));
    }

    @Test
    void ragDebugRejectsKnowledgeBaseNotOwnedByUser() throws Exception {
        when(knowledgeBaseMapper.selectByIdAndUserId(999L, 42L)).thenReturn(null);

        mockMvc.perform(post("/rag/debug")
                        .header("Authorization", bearer(42L, "lyle", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"答案是什么？","knowledgeBaseIds":["999"]}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void ragChunksRequireKnowledgeBaseForNonAdmin() throws Exception {
        mockMvc.perform(get("/rag/chunks")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void memoryControllerUsesCurrentUserForAiProxy() throws Exception {
        when(aiServiceClient.getMemory("42", "100")).thenReturn(Map.of(
                "summary", "长期摘要",
                "short_term", List.of(Map.of("role", "user", "content", "答案是什么？"))
        ));

        mockMvc.perform(get("/memory/current")
                        .header("Authorization", bearer(42L, "lyle", "USER"))
                        .param("conversationId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("长期摘要"))
                .andExpect(jsonPath("$.short_term[0].content").value("答案是什么？"));

        verify(aiServiceClient).getMemory("42", "100");
    }

    @Test
    void agentToolControllerUpdatesTodoDueAtWithCurrentUser() throws Exception {
        when(aiServiceClient.updateTodoDueAt(eq("42"), eq(88L), any())).thenReturn(Map.of(
                "id", 88,
                "title", "产品定位文档",
                "dueAt", "2026-07-03T09:00:00Z"
        ));

        mockMvc.perform(patch("/agent-tools/todos/88/due-at")
                        .header("Authorization", bearer(42L, "lyle", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dueAt":"2026-07-03T09:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(88))
                .andExpect(jsonPath("$.dueAt").value("2026-07-03T09:00:00Z"));

        verify(aiServiceClient).updateTodoDueAt(eq("42"), eq(88L), any());
    }

    @Test
    void managementEndpointsRequireAdminRole() throws Exception {
        mockMvc.perform(get("/prompts")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/evaluation/runs")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/ingest/tasks/task-1")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointsRequireAdminRole() throws Exception {
        mockMvc.perform(get("/admin/overview")
                        .header("Authorization", bearer(42L, "lyle", "USER")))
                .andExpect(status().isForbidden());

        when(adminService.overview()).thenReturn(new AdminOverviewVO(1, 2, 3, 4, 5));

        mockMvc.perform(get("/admin/overview")
                        .header("Authorization", bearer(1L, "admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").value(1))
                .andExpect(jsonPath("$.knowledgeFiles").value(4));
    }

    @Test
    void adminConfigUpdateUsesCurrentAdminUser() throws Exception {
        LocalDateTime now = LocalDateTime.parse("2026-06-30T18:00:00");
        when(adminService.updateAiConfig(eq(1L), any())).thenReturn(
                new com.example.aiapp.domain.vo.AiConfigItemVO("REVIEWER_ENABLED", "true", "是否开启审核", now)
        );

        mockMvc.perform(put("/admin/ai/config")
                        .header("Authorization", bearer(1L, "admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"REVIEWER_ENABLED","value":"true"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("REVIEWER_ENABLED"))
                .andExpect(jsonPath("$.value").value("true"));

        verify(adminService).updateAiConfig(eq(1L), any());
    }

    private String bearer(Long userId, String username, String role) {
        return "Bearer " + jwtService.generateToken(userId, username, role);
    }
}
