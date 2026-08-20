package org.example.naeilbank.service;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.auth.dto.AuthDtos.JoinRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.JoinResponse;
import org.example.naeilbank.domain.auth.dto.AuthDtos.KakaoLoginRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.LoginRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.LogoutRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.RefreshRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.TokenResponse;
import org.example.naeilbank.domain.auth.dto.AuthDtos.UserSummary;
import org.example.naeilbank.domain.auth.service.RefreshTokenService;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.global.oauth.KakaoOAuthService;
import org.example.naeilbank.global.oauth.KakaoUserInfo;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final KakaoOAuthService kakaoOAuthService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public JoinResponse join(JoinRequest joinRequest) {
        if (userRepository.existsByEmail(joinRequest.email())) {
            throw new AuthException(ErrorCode.DUPLICATE_EMAIL);
        }

        String encodedPassword = passwordEncoder.encode(joinRequest.password());
        User user = User.local(joinRequest.email(), encodedPassword);
        userRepository.save(user);

        return new JoinResponse(
                user.getId(),
                "회원가입이 완료되었습니다."
        );
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .filter(found -> found.getPassword() != null)
                .filter(found -> passwordEncoder.matches(request.password(), found.getPassword()))
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        return refreshTokenService.issueTokenPair(user);
    }

    public TokenResponse refresh(RefreshRequest request) {
        return refreshTokenService.rotate(request.refreshToken());
    }

    public void logout(LogoutRequest request) {
        refreshTokenService.logout(request.refreshToken());
    }

    @Transactional(readOnly = true)
    public UserSummary me(String userId) {
        UUID id = UUID.fromString(userId);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        return userSummary(user);
    }

    @Transactional
    public TokenResponse kakaoLogin(KakaoLoginRequest request) {
        KakaoUserInfo kakaoUserInfo;
        try {
            String kakaoAccessToken = kakaoOAuthService.getKakaoAccessToken(request.code());
            kakaoUserInfo = kakaoOAuthService.getKakaoUserInfo(kakaoAccessToken);
        } catch (RuntimeException e) {
            throw new AuthException(ErrorCode.KAKAO_AUTH_FAILED);
        }

        String kakaoId = kakaoId(kakaoUserInfo);
        lockKakaoId(kakaoId);
        User user = userRepository.findByKakaoId(kakaoId)
                .orElseGet(() -> createKakaoUser(kakaoUserInfo, kakaoId));

        return refreshTokenService.issueTokenPair(user);
    }

    private User createKakaoUser(KakaoUserInfo kakaoUserInfo, String kakaoId) {
        if (userRepository.existsByEmailIgnoreCase(kakaoUserInfo.getEmail())) {
            throw new AuthException(ErrorCode.KAKAO_AUTH_FAILED);
        }
        return userRepository.save(User.builder()
                .email(kakaoUserInfo.getEmail())
                .nickname(kakaoUserInfo.getNickname())
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .authProvider("kakao")
                .kakaoId(kakaoId)
                .build());
    }

    private String kakaoId(KakaoUserInfo kakaoUserInfo) {
        if (kakaoUserInfo.getId() == null || kakaoUserInfo.getId() <= 0) {
            throw new AuthException(ErrorCode.KAKAO_AUTH_FAILED);
        }
        return String.valueOf(kakaoUserInfo.getId());
    }

    private void lockKakaoId(String kakaoId) {
        userRepository.lockKakaoId(kakaoId);
    }

    private UserSummary userSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), "ROLE_" + user.getRole().name());
    }

}
