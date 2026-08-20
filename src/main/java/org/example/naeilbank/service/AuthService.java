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
import org.example.naeilbank.domain.auth.dto.AuthDtos.ResendVerificationRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.VerifyEmailRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.VerifyEmailResponse;
import org.example.naeilbank.domain.auth.service.EmailVerificationService;
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
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public JoinResponse join(JoinRequest joinRequest) {
        if (userRepository.existsByEmail(joinRequest.email())) {
            throw new AuthException(ErrorCode.DUPLICATE_EMAIL);
        }

        String encodedPassword = passwordEncoder.encode(joinRequest.password());
        User user = User.local(joinRequest.email(), encodedPassword, joinRequest.name().trim());
        boolean verificationRequired = emailVerificationService.isEnabled();
        if (verificationRequired) {
            emailVerificationService.issueAndSend(user);
        } else {
            user.markEmailVerified();
        }
        userRepository.save(user);

        return new JoinResponse(
                user.getId(),
                verificationRequired
                        ? "인증 코드를 이메일로 보냈습니다. 10분 안에 입력해주세요."
                        : "회원가입이 완료되었습니다.",
                verificationRequired
        );
    }

    @Transactional
    public VerifyEmailResponse verifyEmail(VerifyEmailRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        emailVerificationService.verify(user, request.code());
        return new VerifyEmailResponse(true, "이메일 인증이 완료되었습니다.");
    }

    @Transactional
    public VerifyEmailResponse resendVerification(ResendVerificationRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (user.isEmailVerified()) {
            return new VerifyEmailResponse(true, "이미 인증된 계정입니다. 로그인해주세요.");
        }
        emailVerificationService.issueAndSend(user);
        return new VerifyEmailResponse(false, "인증 코드를 다시 보냈습니다.");
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .filter(found -> found.getPassword() != null)
                .filter(found -> passwordEncoder.matches(request.password(), found.getPassword()))
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (emailVerificationService.isEnabled() && !user.isEmailVerified()) {
            throw new AuthException(ErrorCode.EMAIL_NOT_VERIFIED);
        }

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
        return new UserSummary(user.getId(), user.getEmail(), user.getName(), "ROLE_" + user.getRole().name());
    }

}
