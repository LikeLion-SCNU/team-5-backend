package org.example.naeilbank.global.config;

import org.example.naeilbank.global.config.properties.CorsProperties;
import org.example.naeilbank.global.exception.SecurityErrorResponseWriter;
import org.example.naeilbank.global.jwt.JwtAuthenticationFilter;
import org.example.naeilbank.global.security.AuthRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityConfigCorsTest {

    @Test
    void usesExactConfiguredOriginsForCredentialedCors() {
        CorsProperties corsProperties = new CorsProperties(
                true,
                List.of("https://timebank.hbinserver.cloud", "http://localhost:5173"),
                List.of("GET", "POST", "OPTIONS"),
                List.of("Authorization", "Content-Type", "If-None-Match"),
                List.of("Location", "ETag")
        );
        SecurityConfig securityConfig = new SecurityConfig(
                mock(JwtAuthenticationFilter.class),
                mock(AuthRateLimitFilter.class),
                corsProperties,
                mock(SecurityErrorResponseWriter.class)
        );

        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins())
                .containsExactly("https://timebank.hbinserver.cloud", "http://localhost:5173");
        assertThat(configuration.getAllowedOrigins()).doesNotContain("*");
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getAllowedHeaders()).contains("If-None-Match");
        assertThat(configuration.getExposedHeaders()).contains("ETag");
    }
}
