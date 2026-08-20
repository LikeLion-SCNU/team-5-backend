package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.auth.dto.AuthDtos.JoinRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.JoinResponse;
import org.example.naeilbank.domain.auth.dto.AuthDtos.KakaoLoginRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.LoginRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.LogoutRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.RefreshRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.TokenResponse;
import org.example.naeilbank.domain.auth.dto.AuthDtos.UserSummary;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/join")
    public ResponseEntity<JoinResponse> join(@Valid @RequestBody JoinRequest joinRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.join(joinRequest));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        return ResponseEntity.ok(authService.login(loginRequest));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/kakao")
    public ResponseEntity<TokenResponse> kakaoLogin(@Valid @RequestBody KakaoLoginRequest request) {
        return ResponseEntity.ok(authService.kakaoLogin(request));
    }

    @GetMapping("/me")
    public ResponseEntity<UserSummary> me(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return ResponseEntity.ok(authService.me(authentication.getPrincipal().toString()));
    }
}
