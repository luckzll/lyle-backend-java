package com.example.aiapp.config;

import com.example.aiapp.security.JwtAuthenticationFilter;
import com.example.aiapp.security.RateLimitFilter;
import com.example.aiapp.security.RefreshOriginFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            RefreshOriginFilter refreshOriginFilter
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                        )
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN)
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/auth/login", "/auth/register", "/auth/change-password")
                            .denyAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/auth/captcha/challenge",
                                "/auth/captcha/verify",
                                "/auth/email/code",
                                "/auth/email/login",
                                "/auth/refresh"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/billing/catalog").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/email/options").permitAll()
                        .requestMatchers(
                                "/health",
                                "/internal/ai-usage/events:batch",
                                "/internal/memory-sources:validate",
                                "/billing/webhooks/**",
                                "/runtime-config",
                                "/experience/public-events",
                                "/actuator/health",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/admin/**", "/prompts/**", "/evaluation/**", "/ingest/tasks/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(refreshOriginFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    RefreshOriginFilter refreshOriginFilter(AuthProperties properties) {
        return new RefreshOriginFilter(properties);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
