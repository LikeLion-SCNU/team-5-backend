package org.example.naeilbank.domain.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "media_blobs",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_media_blobs_user_purpose_sha256",
                        columnNames = {"user_id", "purpose", "sha256"}
                ),
                @UniqueConstraint(
                        name = "uk_media_blobs_user_id_id",
                        columnNames = {"user_id", "id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaBlob {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.active;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false)
    private String sha256;

    @Getter(AccessLevel.NONE)
    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public MediaBlob(UUID userId, Purpose purpose, String contentType, String sha256, byte[] content) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must not be empty");
        }
        this.content = Arrays.copyOf(content, content.length);
        this.sizeBytes = content.length;
    }

    public byte[] getContent() {
        return Arrays.copyOf(content, content.length);
    }

    public boolean isActive() {
        return status == Status.active;
    }

    public enum Purpose {
        meal_input,
        face_input,
        face_output_current,
        face_output_improved
    }

    public enum Status {
        active,
        pending_delete,
        deleted
    }
}
