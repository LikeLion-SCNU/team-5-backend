package org.example.naeilbank.domain.face;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.face.FaceSimulationDtos.CreateRequest;
import org.example.naeilbank.domain.face.FaceSimulationDtos.SimulationResponse;
import org.example.naeilbank.domain.face.FaceSimulationDtos.SimulationPage;
import org.example.naeilbank.domain.media.MediaModels.MediaDownload;
import org.example.naeilbank.domain.media.MediaModels.MediaMetadata;
import org.example.naeilbank.domain.media.MediaService;
import org.example.naeilbank.domain.model.entity.Consent;
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
import org.springframework.data.domain.PageRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FaceSimulationService {
    private static final long MAX_ACTIVE_SIMULATIONS_PER_USER = 3;
    private static final List<FaceSimulation.Status> ACTIVE_STATUSES = List.of(
            FaceSimulation.Status.queued,
            FaceSimulation.Status.generating,
            FaceSimulation.Status.processing
    );

    private final FaceSimulationRepository faceSimulationRepository;
    private final FaceSimulationInterlock faceSimulationInterlock;
    private final FaceSimulationOutputRepository outputRepository;
    private final MediaBlobRepository mediaBlobRepository;
    private final MediaService mediaService;
    private final ConsentGuard consentGuard;
    private final UserRepository userRepository;
    private final FaceDataDeletionService deletionService;
    private final Clock clock;

    @Transactional
    public SimulationResponse create(UUID userId, CreateRequest request) {
        lockUser(userId);
        consentGuard.requireGranted(userId, Consent.Purpose.FACE_AI);
        requireOwnedFaceInput(userId, request.sourceMediaId());
        String requestHash = requestHash(request);
        FaceSimulation replay = faceSimulationRepository
                .findByUserIdAndIdempotencyKey(userId, request.idempotencyKey())
                .orElse(null);
        if (replay != null) {
            if (!replay.sameRequest(requestHash)) {
                throw new AuthException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return toResponse(replay, true);
        }
        if (faceSimulationRepository.countByUserIdAndStatusIn(userId, ACTIVE_STATUSES)
                >= MAX_ACTIVE_SIMULATIONS_PER_USER) {
            throw new AuthException(ErrorCode.TOO_MANY_REQUESTS);
        }

        FaceSimulation saved = faceSimulationRepository.saveAndFlush(new FaceSimulation(
                userId,
                request.sourceMediaId(),
                normalizedTrend(request.trendDescription()),
                request.idempotencyKey(),
                requestHash
        ));
        return toResponse(saved, false);
    }

    @Transactional(readOnly = true)
    public SimulationPage list(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new AuthException(ErrorCode.VALIDATION_FAILED);
        }
        var simulations = faceSimulationRepository.findByUserIdOrderByCreatedAtDesc(
                userId,
                PageRequest.of(page, size)
        );
        List<UUID> ids = simulations.stream().map(FaceSimulation::getId).toList();
        Map<UUID, List<FaceSimulationOutput>> outputs = ids.isEmpty()
                ? Map.of()
                : outputRepository.findByUserIdAndSimulationIdInOrderBySimulationIdAscLabelAsc(userId, ids)
                .stream()
                .collect(Collectors.groupingBy(FaceSimulationOutput::getSimulationId));
        return new SimulationPage(
                simulations.stream()
                        .map(simulation -> toResponse(
                                simulation,
                                false,
                                outputs.getOrDefault(simulation.getId(), List.of())
                        ))
                        .toList(),
                page,
                size,
                simulations.hasNext()
        );
    }

    @Transactional(readOnly = true)
    public SimulationResponse get(UUID userId, UUID simulationId) {
        FaceSimulation simulation = faceSimulationRepository.findByIdAndUserId(simulationId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.FACE_SIMULATION_NOT_FOUND));
        return toResponse(simulation, false);
    }

    @Transactional
    public SimulationResponse cancel(UUID userId, UUID simulationId) {
        faceSimulationInterlock.requestCancellation(simulationId, userId);
        try {
            faceSimulationRepository.cancelActive(simulationId, userId, Instant.now(clock));
            FaceSimulation simulation = faceSimulationRepository.findByIdAndUserId(simulationId, userId)
                    .orElseThrow(() -> new AuthException(ErrorCode.FACE_SIMULATION_NOT_FOUND));
            return toResponse(simulation, false);
        } finally {
            faceSimulationInterlock.releaseCancellation(simulationId, userId);
        }
    }

    public void delete(UUID userId, UUID simulationId) {
        deletionService.deleteSimulation(userId, simulationId);
    }

    @Transactional(readOnly = true)
    public MediaMetadata faceMediaMetadata(UUID userId, UUID mediaId) {
        MediaMetadata metadata = mediaService.metadata(userId, mediaId);
        requireFacePurpose(metadata);
        return metadata;
    }

    @Transactional(readOnly = true)
    public MediaDownload faceMediaDownload(UUID userId, UUID mediaId) {
        MediaDownload download = mediaService.download(userId, mediaId);
        requireFacePurpose(download.metadata());
        return download;
    }

    public void deleteFaceMedia(UUID userId, UUID mediaId) {
        deletionService.deleteMedia(userId, mediaId);
    }

    private void requireOwnedFaceInput(UUID userId, UUID sourceMediaId) {
        mediaBlobRepository.findByIdAndUserIdAndPurposeAndStatus(
                        sourceMediaId,
                        userId,
                        MediaBlob.Purpose.face_input,
                        MediaBlob.Status.active
                )
                .orElseThrow(() -> new AuthException(ErrorCode.MEDIA_NOT_FOUND));
    }

    private void requireFacePurpose(MediaMetadata metadata) {
        if (metadata.purpose() != MediaBlob.Purpose.face_input
                && metadata.purpose() != MediaBlob.Purpose.face_output_current
                && metadata.purpose() != MediaBlob.Purpose.face_output_improved) {
            throw new AuthException(ErrorCode.MEDIA_NOT_FOUND);
        }
    }

    private SimulationResponse toResponse(FaceSimulation simulation, boolean replayed) {
        return toResponse(
                simulation,
                replayed,
                outputRepository.findByUserIdAndSimulationIdOrderByLabelAsc(
                        simulation.getUserId(),
                        simulation.getId()
                )
        );
    }

    private SimulationResponse toResponse(
            FaceSimulation simulation,
            boolean replayed,
            List<FaceSimulationOutput> outputs
    ) {
        return SimulationResponse.from(
                simulation,
                replayed,
                outputs
        );
    }

    private String requestHash(CreateRequest request) {
        return sha256(String.join("|",
                request.sourceMediaId().toString(),
                normalizedTrend(request.trendDescription()),
                Boolean.toString(request.selfPhotoConfirmed()),
                Boolean.toString(request.adultConfirmed()),
                Boolean.toString(request.disclaimerAccepted())
        ));
    }

    private String normalizedTrend(String trendDescription) {
        return trendDescription == null ? "" : trendDescription.trim();
    }

    private void lockUser(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

}
