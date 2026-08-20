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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "face_simulation_outputs",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_face_simulation_outputs_simulation_label",
                        columnNames = {"simulation_id", "label"}
                ),
                @UniqueConstraint(
                        name = "face_simulation_outputs_media_blob_id_key",
                        columnNames = "media_blob_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaceSimulationOutput {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "simulation_id", nullable = false)
    private UUID simulationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "media_blob_id", nullable = false, unique = true)
    private UUID mediaBlobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", nullable = false)
    private Label label;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public FaceSimulationOutput(
            UUID simulationId,
            UUID userId,
            UUID mediaBlobId,
            Label label,
            String modelVersion,
            String promptVersion
    ) {
        this.simulationId = Objects.requireNonNull(simulationId, "simulationId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.mediaBlobId = Objects.requireNonNull(mediaBlobId, "mediaBlobId");
        this.label = Objects.requireNonNull(label, "label");
        this.modelVersion = requireText(modelVersion, "modelVersion");
        this.promptVersion = requireText(promptVersion, "promptVersion");
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Label {
        current,
        improved
    }
}
