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
        return User.builder()
                .email(email)
                .passwordHash(passwordHash)
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
}
