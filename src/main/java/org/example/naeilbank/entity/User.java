package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "nickname")
    private String nickname;

    @Column(name = "name")
    private String name;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "email_verification_code")
    private String emailVerificationCode;

    @Column(name = "email_verification_expires_at")
    private Instant emailVerificationExpiresAt;

    @Column(name = "auth_provider", nullable = false)
    private String authProvider;

    @Column(name = "kakao_id", unique = true)
    private String kakaoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Column(name = "notify_enabled", nullable = false)
    @Builder.Default
    private boolean notifyEnabled = false;

    @Column(name = "notify_time", nullable = false)
    @Builder.Default
    private LocalTime notifyTime = LocalTime.of(8, 0); // 기본값 08:00

    @Column(name = "protection_mode", nullable = false)
    @Builder.Default
    private boolean protectionMode = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public static User local(String email, String passwordHash) {
        return local(email, passwordHash, split(email));
    }

    private static String split(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    public static User local(String email, String passwordHash, String name) {
        return User.builder()
                .email(email)
                .passwordHash(passwordHash)
                .name(name)
                .authProvider("email")
                .role(Role.USER)
                .notifyEnabled(false)
                .notifyTime(LocalTime.of(8, 0))
                .protectionMode(false)
                .createdAt(Instant.now())
                .build();
    }

    public static User kakao(String email, String nickname, String passwordHash) {
        return User.builder()
                .email(email)
                .nickname(nickname)
                .name(nickname)
                .emailVerified(true)
                .passwordHash(passwordHash)
                .authProvider("kakao")
                .role(Role.USER)
                .notifyEnabled(false)
                .notifyTime(LocalTime.of(8, 0))
                .protectionMode(false)
                .createdAt(Instant.now())
                .build();
    }

    public String getPassword() {
        return passwordHash;
    }

    public void enableProtectionMode() {
        this.protectionMode = true;
    }

    public void disableProtectionMode() {
        this.protectionMode = false;
    }

    public void changeNotificationPreference(boolean enabled, LocalTime notifyTime) {
        this.notifyEnabled = enabled;
        this.notifyTime = notifyTime;
    }

    public void issueEmailVerification(String code, Instant expiresAt) {
        this.emailVerificationCode = code;
        this.emailVerificationExpiresAt = expiresAt;
        this.emailVerified = false;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
        this.emailVerificationCode = null;
        this.emailVerificationExpiresAt = null;
    }
}
