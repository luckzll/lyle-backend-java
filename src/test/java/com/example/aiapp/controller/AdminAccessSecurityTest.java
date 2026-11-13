package com.example.aiapp.controller;

import com.example.aiapp.config.*;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.security.*;
import com.example.aiapp.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AdminUserController.class, AdminKnowledgeController.class})
@ContextConfiguration(classes = {AdminUserController.class, AdminKnowledgeController.class, SecurityConfig.class,
        JwtService.class, JwtAuthenticationFilter.class, RateLimitFilter.class})
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class, RedisProperties.class})
@TestPropertySource(properties = {
        "app.auth.jwt-secret=admin-security-test-secret-with-at-least-32-bytes",
        "app.auth.jwt-key-id=test", "app.auth.jwt-ttl-minutes=15",
        "app.auth.issuer=lyle-ai", "app.auth.audience=lyle-web", "app.redis.enabled=false"
})
class AdminAccessSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean AdminUserService users;
    @MockBean KnowledgeBaseMapper bases;
    @MockBean KnowledgeFileMapper files;
    @MockBean FileService fileService;
    @MockBean KnowledgeBaseService baseService;
    @MockBean AuditLogService audit;
    @MockBean AuthSessionService sessions;

    private String token(String role) {
        when(sessions.isAccessValid(1L, 99L, 1)).thenReturn(true);
        return "Bearer " + jwt.generateToken(99L, "admin-test", role, 1L, 1);
    }

    @Test void anonymousAndRegularUsersCannotReadOrMutateAdminResources() throws Exception {
        for (String path : List.of("/admin/users", "/admin/users/7", "/admin/users/7/credits", "/admin/users/7/ledger", "/admin/knowledge-bases", "/admin/knowledge-bases/10/files")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", token("USER"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/users/7/credits/grants").header("Authorization", token("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(grantBody(1)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/knowledge-bases/10/files/3/rebuild").header("Authorization", token("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, bases, files, fileService, baseService, audit);
    }

    @Test void administratorQueriesKeepIdsAsStringsAndExposeOnlyPublicMetadata() throws Exception {
        when(users.list(2, 20, "test", "ACTIVE")).thenReturn(new PageVO<>(List.of(
                new AdminUserService.UserView("999999999999999999", "test", "test@example.com", "USER", "ACTIVE", null, null)), 21, 2, 20));
        mvc.perform(get("/admin/users").param("page", "2").param("keyword", "test").param("status", "ACTIVE")
                        .header("Authorization", token("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.records[0].id").value("999999999999999999"))
                .andExpect(jsonPath("$.records[0].email").value("test@example.com"))
                .andExpect(jsonPath("$.records[0].passwordHash").doesNotExist());
    }

    @Test void invalidPaginationAndFiltersAreBadRequestsBeforeAnyDataAccess() throws Exception {
        for (String query : List.of("page=0", "size=101", "status=UNKNOWN", "keyword=" + "a".repeat(255))) {
            mvc.perform(get("/admin/users?" + query).header("Authorization", token("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/admin/knowledge-bases/10/files?status=UNKNOWN").header("Authorization", token("ADMIN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users, bases, files);
    }

    @Test void validGrantUsesTheAuthenticatedAdministratorAndRejectsInvalidQuantities() throws Exception {
        when(users.grant(eq(99L), eq(7L), any())).thenReturn(new AdminUserService.CreditView(true, "ACTIVE", "1", "1", "0", null));
        mvc.perform(post("/admin/users/7/credits/grants").header("Authorization", token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(grantBody(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableCreditMicros").value("1"));
        mvc.perform(post("/admin/users/7/credits/grants").header("Authorization", token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(grantBody(0)))
                .andExpect(status().isBadRequest());
        verify(users, times(1)).grant(eq(99L), eq(7L), argThat(request -> request.creditMicros() == 1));
    }

    private String grantBody(long quantity) {
        return "{\"requestId\":\"admin-test-request-0001\",\"creditMicros\":" + quantity + ",\"validityDays\":30,\"reason\":\"business correction\"}";
    }
}
