package org.example.naeilbank.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expirationMs = 1000L * 60 * 60 * 24; // 24시간

    // application.yml 의 jwt.secret 값 주입 (최소 32자 이상이어야 안전함)
    public JwtTokenProvider(@Value("${jwt.secret:TomorrowBankVeryLongSecretKeyForJWTTokenSecurity123456}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // 1. 토큰 생성 (role 포함)
    public String createToken(Long userId, String email, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role) // 예: "ROLE_USER", "ROLE_ADMIN" (Spring Security는 ROLE_ 접두사 권장)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey) // 0.12.x는 알고리즘 인자 생략 가능
                .compact();
    }

    // 2. 토큰 검증
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token); // 0.12.x 파싱 방식
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // 만료, 서명 불일치 등 위조 토큰 처리
            return false;
        }
    }

    // 3. Spring Security Authentication 객체 생성
    public Authentication getAuthentication(String token) {
        Claims claims = getClaims(token);
        String role = claims.get("role", String.class);

        // Security 권한 목록 생성 (ROLE_USER, ROLE_ADMIN)
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(role);

        // Principal로 userId(또는 email) 전달
        return new UsernamePasswordAuthenticationToken(
                claims.getSubject(), // Principal (userId)
                "",
                Collections.singletonList(authority)
        );
    }

    // Claims 추출 공통 메서드
    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload(); // 0.12.x에서는 getBody() 대신 getPayload() 사용
    }
}