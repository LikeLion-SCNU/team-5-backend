package org.example.naeilbank.domain.face;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.naeilbank.domain.model.entity.FaceSimulation;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FaceSimulationDtos {
    private FaceSimulationDtos() {
    }

    public record CreateRequest(
            @NotNull UUID sourceMediaId,
            @NotBlank @Size(max = 128) String idempotencyKey,
            @Size(max = 500) String trendDescription,
            @AssertTrue boolean selfPhotoConfirmed,
            @AssertTrue boolean adultConfirmed,
            @AssertTrue boolean disclaimerAccepted
    ) {
    }

    public record SimulationResponse(
            UUID id,
            UUID sourceMediaId,
            String status,
            String trendDescription,
            boolean replayed,
            Instant createdAt,
            Instant updatedAt,
            List<OutputResponse> outputs
    ) {
        static SimulationResponse from(
                FaceSimulation simulation,
                boolean replayed,
                List<FaceSimulationOutput> outputs
        ) {
            return new SimulationResponse(
                    simulation.getId(),
                    simulation.getSourceMediaId(),
                    simulation.getStatus().name(),
                    simulation.getTrendDescription(),
                    replayed,
                    simulation.getCreatedAt(),
                    simulation.getUpdatedAt(),
                    outputs.stream().map(OutputResponse::from).toList()
            );
        }
    }

    public record OutputResponse(
            String label,
            UUID mediaId,
            String modelVersion,
            String promptVersion,
            Instant createdAt
    ) {
        static OutputResponse from(FaceSimulationOutput output) {
            return new OutputResponse(
                    output.getLabel().name(),
                    output.getMediaBlobId(),
                    output.getModelVersion(),
                    output.getPromptVersion(),
                    output.getCreatedAt()
            );
        }
    }
}
