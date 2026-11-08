package com.example.aiapp.security;

import com.example.aiapp.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RefreshOriginFilterTest {
    @Test void adminDevelopmentHostsCanRefreshWhenTheProxyPreservesTheBrowserHost() throws Exception {
        for (String host : new String[]{"127.0.0.1", "localhost"}) {
            var properties = mock(AuthProperties.class);
            when(properties.allowedOrigins()).thenReturn("http://127.0.0.1:5176,http://localhost:5176");
            var filter = new RefreshOriginFilter(properties);
            var request = new MockHttpServletRequest("POST", "/api/auth/refresh");
            request.setContextPath("/api");
            request.addHeader("Origin", "http://" + host + ":5176");
            request.addHeader("Host", host + ":5176");
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }
    }

    @Test void untrustedOriginsAndRewrittenHostsRemainRejected() throws Exception {
        for (String[] headers : new String[][]{{"https://untrusted.example", "localhost:5176"}, {"http://localhost:5176", "127.0.0.1:8080"}}) {
            var properties = mock(AuthProperties.class);
            when(properties.allowedOrigins()).thenReturn("http://127.0.0.1:5176,http://localhost:5176");
            var request = new MockHttpServletRequest("POST", "/auth/refresh");
            request.addHeader("Origin", headers[0]);
            request.addHeader("Host", headers[1]);
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            new RefreshOriginFilter(properties).doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(403);
            verifyNoInteractions(chain);
        }
    }
}
