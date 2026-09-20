package com.example.aiapp.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void walksPastTrustedProxies() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.20.30.40");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.10.10.10");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void ignoresSpoofedPrefixBeforeUntrustedHop() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.20.30.40");
        request.addHeader("X-Forwarded-For", "192.0.2.99, 198.51.100.7, 10.10.10.10");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void malformedAndEmptyTrustedSideHopsFailClosed() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        for (String header : new String[]{"198.51.100.7, 999.1.1.1", "198.51.100.7,", "1234", "dead.beef"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.20.30.40");
            request.addHeader("X-Forwarded-For", header);
            assertThat(resolver.resolve(request)).isEqualTo("10.20.30.40");
        }
    }

    @Test
    void supportsIpv6ProxyChain() {
        ClientIpResolver resolver = new ClientIpResolver("fd00::/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("fd00::1");
        request.addHeader("X-Forwarded-For", "2001:db8::99, 2001:db8::7, fd00::2");
        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::7");
    }

    @Test
    void invalidForwardedAddressFailsClosed() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "attacker.example");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }
}
