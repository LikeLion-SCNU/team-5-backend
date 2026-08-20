package org.example.naeilbank.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "web_push_subscriptions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "web_push_subscriptions_endpoint_hash_key",
                        columnNames = "endpoint_hash"
                ),
                @UniqueConstraint(
                        name = "uk_web_push_subscriptions_user_id_id",
                        columnNames = {"user_id", "id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebPushSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "endpoint_hash", nullable = false, unique = true)
    private String endpointHash;

    @Column(name = "endpoint_ciphertext", nullable = false)
    private String endpointCiphertext;

    @Column(name = "p256dh_ciphertext", nullable = false)
    private String p256dhCiphertext;

    @Column(name = "auth_ciphertext", nullable = false)
    private String authCiphertext;

    @Column(name = "expiration_time")
    private Instant expirationTime;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public static WebPushSubscription create(
            UUID userId,
            String endpointHash,
            String endpointCiphertext,
            String p256dhCiphertext,
            String authCiphertext,
            Instant expirationTime,
            Instant now
    ) {
        WebPushSubscription subscription = new WebPushSubscription();
        subscription.userId = userId;
        subscription.endpointHash = endpointHash;
        subscription.endpointCiphertext = endpointCiphertext;
        subscription.p256dhCiphertext = p256dhCiphertext;
        subscription.authCiphertext = authCiphertext;
        subscription.expirationTime = expirationTime;
        subscription.active = true;
        subscription.createdAt = now;
        return subscription;
    }

    public void refresh(String endpointCiphertext, String p256dhCiphertext, String authCiphertext, Instant expirationTime) {
        this.endpointCiphertext = endpointCiphertext;
        this.p256dhCiphertext = p256dhCiphertext;
        this.authCiphertext = authCiphertext;
        this.expirationTime = expirationTime;
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
