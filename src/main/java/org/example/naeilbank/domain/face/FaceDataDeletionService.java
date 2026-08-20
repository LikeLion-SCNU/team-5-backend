package org.example.naeilbank.domain.face;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.audit.AuditAppendService;
import org.example.naeilbank.domain.media.MediaModels.MediaMetadata;
import org.example.naeilbank.domain.media.MediaService;
import org.example.naeilbank.domain.model.entity.FaceSimulation;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.example.naeilbank.domain.model.repository.FaceSimulationOutputRepository;
import org.example.naeilbank.domain.model.repository.FaceSimulationRepository;
import org.example.naeilbank.domain.model.repository.MediaBlobRepository;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FaceDataDeletionService {
    private final FaceSimulationRepository simulationRepository;
    private final FaceSimulationOutputRepository outputRepository;
    private final MediaBlobRepository mediaBlobRepository;
    private final MediaService mediaService;
    private final UserRepository userRepository;
    private final AuditAppendService auditAppendService;

    @Transactional
    public void deleteSimulation(UUID userId, UUID simulationId) {
        lockUser(userId);
        FaceSimulation simulation = simulationRepository.findOwnedForUpdate(simulationId, userId).orElse(null);
        if (simulation == null) {
            return;
        }
        List<UUID> outputMediaIds = outputRepository
                .findByUserIdAndSimulationIdOrderByLabelAsc(userId, simulationId).stream()
                .map(FaceSimulationOutput::getMediaBlobId)
                .toList();
        UUID sourceMediaId = simulation.getSourceMediaId();
        simulationRepository.delete(simulation);
        simulationRepository.flush();
        deleteMedia(userId, outputMediaIds);
        if (!simulationRepository.existsByUserIdAndSourceMediaId(userId, sourceMediaId)) {
            mediaService.delete(userId, sourceMediaId);
        }
        auditAppendService.appendFaceDeletion(userId, "FACE_SIMULATION", simulationId);
    }

    @Transactional
    public void deleteMedia(UUID userId, UUID mediaId) {
        lockUser(userId);
        MediaMetadata metadata = metadataOrNull(userId, mediaId);
        if (metadata == null) {
            return;
        }
        requireFacePurpose(metadata);
        if (metadata.purpose() == MediaBlob.Purpose.face_input) {
            deleteBySource(userId, mediaId);
            mediaService.delete(userId, mediaId);
            auditAppendService.appendFaceDeletion(userId, "FACE_INPUT_MEDIA", mediaId);
            return;
        }
        outputRepository.findByUserIdAndMediaBlobId(userId, mediaId).ifPresent(outputRepository::delete);
        outputRepository.flush();
        mediaService.delete(userId, mediaId);
        auditAppendService.appendFaceDeletion(userId, "FACE_OUTPUT_MEDIA", mediaId);
    }

    private void deleteBySource(UUID userId, UUID sourceMediaId) {
        List<FaceSimulation> simulations = simulationRepository.findBySourceForUpdate(userId, sourceMediaId);
        List<UUID> simulationIds = simulations.stream().map(FaceSimulation::getId).toList();
        List<UUID> outputMediaIds = simulationIds.isEmpty()
                ? List.of()
                : outputRepository.findByUserIdAndSimulationIdInOrderBySimulationIdAscLabelAsc(
                        userId,
                        simulationIds
                ).stream().map(FaceSimulationOutput::getMediaBlobId).toList();
        simulationRepository.deleteAll(simulations);
        simulationRepository.flush();
        deleteMedia(userId, outputMediaIds);
        for (FaceSimulation simulation : simulations) {
            auditAppendService.appendFaceDeletion(userId, "FACE_SIMULATION", simulation.getId());
        }
    }

    private void deleteMedia(UUID userId, List<UUID> mediaIds) {
        for (UUID mediaId : mediaIds) {
            mediaService.delete(userId, mediaId);
        }
    }

    private MediaMetadata metadataOrNull(UUID userId, UUID mediaId) {
        return mediaBlobRepository.findMetadata(mediaId, userId, MediaBlob.Status.active)
                .map(MediaMetadata::from)
                .orElse(null);
    }

    private void requireFacePurpose(MediaMetadata metadata) {
        if (metadata.purpose() != MediaBlob.Purpose.face_input
                && metadata.purpose() != MediaBlob.Purpose.face_output_current
                && metadata.purpose() != MediaBlob.Purpose.face_output_improved) {
            throw new AuthException(ErrorCode.MEDIA_NOT_FOUND);
        }
    }

    private void lockUser(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
    }
}
