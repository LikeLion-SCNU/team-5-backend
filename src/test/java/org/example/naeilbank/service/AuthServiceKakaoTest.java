package org.example.naeilbank.service;

import org.example.naeilbank.domain.auth.dto.AuthDtos.KakaoLoginRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.TokenResponse;
import org.example.naeilbank.domain.auth.dto.AuthDtos.UserSummary;
import org.example.naeilbank.domain.auth.service.RefreshTokenService;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.oauth.KakaoOAuthService;
import org.example.naeilbank.global.oauth.KakaoUserInfo;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceKakaoTest {
    @Mock
    UserRepository userRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    KakaoOAuthService kakaoOAuthService;
    @Mock
    RefreshTokenService refreshTokenService;
    @Mock
    org.example.naeilbank.domain.auth.service.EmailVerificationService emailVerificationService;

    @Test
    void kakaoLoginCreatesUserByKakaoIdAndFallbackEmail() {
        AuthService authService = authService();
        KakaoUserInfo kakaoUserInfo = new KakaoUserInfo(3001L, "kakao_3001@users.invalid", "fallback-user");
        TokenResponse tokenResponse = tokenResponse(kakaoUserInfo.getEmail());
        when(kakaoOAuthService.getKakaoAccessToken("code")).thenReturn("access-token");
        when(kakaoOAuthService.getKakaoUserInfo("access-token")).thenReturn(kakaoUserInfo);
        when(userRepository.findByKakaoId("3001")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("kakao_3001@users.invalid")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded-random-secret");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenService.issueTokenPair(any(User.class))).thenReturn(tokenResponse);

        TokenResponse result = authService.kakaoLogin(new KakaoLoginRequest("code"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getKakaoId()).isEqualTo("3001");
        assertThat(saved.getValue().getEmail()).isEqualTo("kakao_3001@users.invalid");
        assertThat(saved.getValue().getAuthProvider()).isEqualTo("kakao");
        assertThat(result).isSameAs(tokenResponse);
    }

    @Test
    void kakaoLoginReusesExistingUserBySameKakaoId() {
        AuthService authService = authService();
        User existing = User.builder()
                .id(UUID.randomUUID())
                .email("kakao_3002@users.invalid")
                .authProvider("kakao")
                .kakaoId("3002")
                .build();
        TokenResponse tokenResponse = tokenResponse(existing.getEmail());
        when(kakaoOAuthService.getKakaoAccessToken("code")).thenReturn("access-token");
        when(kakaoOAuthService.getKakaoUserInfo("access-token"))
                .thenReturn(new KakaoUserInfo(3002L, "changed@example.com", "existing-user"));
        when(userRepository.findByKakaoId("3002")).thenReturn(Optional.of(existing));
        when(refreshTokenService.issueTokenPair(existing)).thenReturn(tokenResponse);

        TokenResponse result = authService.kakaoLogin(new KakaoLoginRequest("code"));

        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).existsByEmailIgnoreCase(any());
        assertThat(result).isSameAs(tokenResponse);
    }

    @Test
    void kakaoLoginFailsClosedWhenFallbackEmailCollidesWithExistingAccount() {
        AuthService authService = authService();
        when(kakaoOAuthService.getKakaoAccessToken("code")).thenReturn("access-token");
        when(kakaoOAuthService.getKakaoUserInfo("access-token"))
                .thenReturn(new KakaoUserInfo(3003L, "kakao_3003@users.invalid", "collision"));
        when(userRepository.findByKakaoId("3003")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("kakao_3003@users.invalid")).thenReturn(true);

        assertThatThrownBy(() -> authService.kakaoLogin(new KakaoLoginRequest("code")))
                .isInstanceOf(AuthException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void kakaoLoginFailsClosedWhenProviderEmailCollidesWithLocalAccount() {
        AuthService authService = authService();
        when(kakaoOAuthService.getKakaoAccessToken("code")).thenReturn("access-token");
        when(kakaoOAuthService.getKakaoUserInfo("access-token"))
                .thenReturn(new KakaoUserInfo(3004L, "shared@example.com", "collision"));
        when(userRepository.findByKakaoId("3004")).thenReturn(Optional.empty());
        when(userRepository.existsByEmailIgnoreCase("shared@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.kakaoLogin(new KakaoLoginRequest("code")))
                .isInstanceOf(AuthException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void kakaoLoginFailsClosedWhenKakaoIdIsNotPositive() {
        AuthService authService = authService();
        when(kakaoOAuthService.getKakaoAccessToken("code")).thenReturn("access-token");
        when(kakaoOAuthService.getKakaoUserInfo("access-token"))
                .thenReturn(new KakaoUserInfo(0L, "kakao_0@users.invalid", "zero"));

        assertThatThrownBy(() -> authService.kakaoLogin(new KakaoLoginRequest("code")))
                .isInstanceOf(AuthException.class);
        verify(userRepository, never()).findByKakaoId(any());
        verify(userRepository, never()).save(any(User.class));
    }

    private AuthService authService() {
        return new AuthService(userRepository, passwordEncoder, kakaoOAuthService, refreshTokenService, emailVerificationService);
    }

    private TokenResponse tokenResponse(String email) {
        return new TokenResponse(
                "access-token",
                "refresh-token",
                "Bearer",
                1800,
                new UserSummary(UUID.randomUUID(), email, "카카오사용자", "ROLE_USER")
        );
    }
}
