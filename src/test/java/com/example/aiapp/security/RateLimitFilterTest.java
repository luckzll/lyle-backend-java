package com.example.aiapp.security;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.config.RateLimitProperties;
import com.example.aiapp.config.RedisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitFilterTest {
    @Test
    @SuppressWarnings("unchecked")
    void localWorkspaceSharesChatBudgetAndCannotRotateSpoofedIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimitProperties(20, 2, 10, 8, 30, 6, "10.0.0.0/8"),
                new RedisProperties(false), mock(ObjectProvider.class),
                new ObjectMapper(), mock(AuthProperties.class));
        String[] paths = {"/api/chat/stream", "/api/local-workspace/step/stream", "/api/local-workspace/step/stream"};
        for (int i = 0; i < paths.length; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", paths[i]);
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("X-Forwarded-For", "192.0.2." + (i + 1) + ", 198.51.100.7");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> res.getWriter().write("allowed"));
            assertThat(response.getStatus()).isEqualTo(i < 2 ? 200 : 429);
        }
    }
}
