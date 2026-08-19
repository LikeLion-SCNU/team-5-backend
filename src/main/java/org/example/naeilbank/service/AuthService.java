package org.example.naeilbank.service;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.dto.JoinRequest;
import org.example.naeilbank.dto.JoinResponse;
import org.example.naeilbank.dto.LoginResponse;
import org.example.naeilbank.entity.Provider;
import org.example.naeilbank.entity.Role;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.example.naeilbank.global.oauth.KakaoOAuthService;
import org.example.naeilbank.global.oauth.KakaoUserInfo;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    private final KakaoOAuthService kakaoOAuthService;

    @Transactional
    public JoinResponse userRegister(JoinRequest joinRequest) {
        if (userRepository.existsByEmail(joinRequest.getEmail())) {
            throw new AuthException(ErrorCode.DUPLICATE_EMAIL);
        }

        String encodedPassword = passwordEncoder.encode(joinRequest.getPassword());

        User user = User.builder()
                .email(joinRequest.getEmail())
                .password(encodedPassword)
                .role(Role.USER)
                .provider(Provider.LOCAL)
                .notifyTime(LocalTime.of(8, 0))
                .build();

        userRepository.save(user);

        return new JoinResponse(
                user.getId(),
                "회원가입이 완료되었습니다."
        );
    }

    @Transactional
    public LoginResponse kakaoLogin(String code) {
        String kakaoAccessToken = kakaoOAuthService.getKakaoAccessToken(code);
        KakaoUserInfo kakaoUserInfo = kakaoOAuthService.getKakaoUserInfo(kakaoAccessToken);

        // 2. DB 저장 및 JWT 발급 테스트
        User user = userRepository.findByEmail(kakaoUserInfo.getEmail())
                .orElseGet(() -> userRepository.save(User.builder()
                        .email(kakaoUserInfo.getEmail())
                        .name(kakaoUserInfo.getNickname())
                        .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                        .role(Role.USER)
                        .provider(Provider.KAKAO)
                        .notifyTime(LocalTime.of(8, 0))
                        .build()));

        String token = jwtTokenProvider.createToken(user.getId(), user.getEmail(), user.getRole().name());
        return new LoginResponse(token, user.getEmail(), user.getRole().name());
    }
}