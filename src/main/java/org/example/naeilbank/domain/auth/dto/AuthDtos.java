package org.example.naeilbank.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record JoinRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 50) String name
    ) {
    }

    public record JoinResponse(UUID userId, String message, boolean verificationRequired) {
    }

    public record VerifyEmailRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(min = 6, max = 6) String code
    ) {
    }

    public record ResendVerificationRequest(
            @Email @NotBlank @Size(max = 254) String email
    ) {
    }

    public record VerifyEmailResponse(boolean verified, String message) {
    }

    public record LoginRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password
    ) {
    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            UserSummary user
    ) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
    }

    public record LogoutRequest(@NotBlank @Size(max = 512) String refreshToken) {
    }

    public record KakaoLoginRequest(@NotBlank @Size(max = 1024) String code) {
    }

    public record UserSummary(UUID id, String email, String name, String role) {
    }
}
