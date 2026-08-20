package org.example.naeilbank.global.jwt;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {
    private final JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void asyncDispatchWithBearerTokenRestoresAuthentication() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "user-id", null, List.of()
        );
        when(tokenProvider.getAuthentication("access-token")).thenReturn(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.ASYNC);
        request.addHeader("Authorization", "Bearer access-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(tokenProvider).getAuthentication("access-token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }
}
