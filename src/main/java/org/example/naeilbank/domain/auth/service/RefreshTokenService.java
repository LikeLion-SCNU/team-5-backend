package org.example.naeilbank.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.auth.dto.AuthDtos.TokenResponse;
import org.example.naeilbank.domain.auth.dto.AuthDtos.UserSummary;
import org.example.naeilbank.domain.auth.entity.RefreshToken;
import org.example.naeilbank.domain.auth.repository.RefreshTokenRepository;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private static final int TOKEN_INSERT_ATTEMPTS = 5;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenHasher refreshTokenHasher;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;

    @Transactional
    public TokenResponse issueTokenPair(User user) {
        IssuedRefreshToken refreshToken = persistNewRefreshToken(
                user.getId(),
                UUID.randomUUID(),
                null,
                clock.instant()
        );
        String accessToken = jwtTokenProvider.createToken(user.getId(), user.getEmail(), user.getRole().name());
        return tokenResponse(accessToken, refreshToken.rawToken(), user);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public TokenResponse rotate(String rawRefreshToken) {
        ensureRefreshTokenShape(rawRefreshToken);

        Instant now = clock.instant();
        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        RefreshToken current = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (current.getRevokedAt() != null || current.getUsedAt() != null) {
            current.markReuseDetected(now);
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now);
            throw new AuthException(ErrorCode.REFRESH_TOKEN_REUSED);
        }
        if (current.isExpired(now)) {
            current.revoke(now);
            throw new AuthException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        User user = userRepository.findById(current.getUserId())
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_REFRESH_TOKEN));
        IssuedRefreshToken nextRefreshToken = persistNewRefreshToken(
                user.getId(),
                current.getFamilyId(),
                tokenHash,
                now
        );
        current.markRotated(now);
        String accessToken = jwtTokenProvider.createToken(user.getId(), user.getEmail(), user.getRole().name());
        return tokenResponse(accessToken, nextRefreshToken.rawToken(), user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        ensureRefreshTokenShape(rawRefreshToken);
        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        Instant now = clock.instant();
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), now));
    }

    private IssuedRefreshToken persistNewRefreshToken(
            UUID userId,
            UUID familyId,
            String previousTokenHash,
            Instant now
    ) {
        for (int attempt = 0; attempt < TOKEN_INSERT_ATTEMPTS; attempt++) {
            String rawToken = refreshTokenIssuer.issue();
            String tokenHash = refreshTokenHasher.hash(rawToken);
            int inserted = refreshTokenRepository.insertIfTokenHashAbsent(
                    UUID.randomUUID(),
                    userId,
                    tokenHash,
                    familyId,
                    previousTokenHash,
                    now.plus(jwtTokenProvider.refreshTokenTtl()),
                    now
            );
            if (inserted == 1) {
                return new IssuedRefreshToken(rawToken);
            }
        }
        throw new IllegalStateException("Could not allocate a unique refresh token");
    }

    private TokenResponse tokenResponse(String accessToken, String refreshToken, User user) {
        return new TokenResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtTokenProvider.accessTokenTtl().toSeconds(),
                new UserSummary(user.getId(), user.getEmail(), "ROLE_" + user.getRole().name())
        );
    }

    private void ensureRefreshTokenShape(String refreshToken) {
        if (refreshToken == null || !refreshToken.matches("[A-Za-z0-9_-]{32,512}")) {
            throw new AuthException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private record IssuedRefreshToken(String rawToken) {
    }
}
