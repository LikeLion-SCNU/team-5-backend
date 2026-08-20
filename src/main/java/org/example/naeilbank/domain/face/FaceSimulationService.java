package org.example.naeilbank.domain.face;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.face.FaceSimulationDtos.CreateRequest;
import org.example.naeilbank.domain.face.FaceSimulationDtos.SimulationResponse;
import org.example.naeilbank.domain.media.GeneratedMediaStore;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class FaceSimulationService {
    private static final List<FaceSimulation.Status> DUE_STATUSES = List.of(
            FaceSimulation.Status.queued,
            FaceSimulation.Status.generating
    );

    private final FaceSimulationRepository faceSimulationRepository;
    private final FaceSimulationOutputRepository outputRepository;
    private final MediaBlobRepository mediaBlobRepository;
    private final MediaService mediaService;
    private final ConsentGuard consentGuard;
    private final UserRepository userRepository;
    private final FaceSimulationImageGenerator imageGenerator;
    private final Clock clock;
    private final ObjectProvider<TransactionTemplate> transactionTemplate;

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
    public List<SimulationResponse> list(UUID userId) {
        return faceSimulationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(simulation -> toResponse(simulation, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public SimulationResponse get(UUID userId, UUID simulationId) {
        FaceSimulation simulation = faceSimulationRepository.findByIdAndUserId(simulationId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.FACE_SIMULATION_NOT_FOUND));
        return toResponse(simulation, false);
    }

    @Transactional
    public SimulationResponse cancel(UUID userId, UUID simulationId) {
        FaceSimulation simulation = faceSimulationRepository.findOwnedForUpdate(simulationId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.FACE_SIMULATION_NOT_FOUND));
        if (simulation.canCancel()) {
            simulation.markCancelled(Instant.now(clock));
        }
        return toResponse(simulation, false);
    }

    @Transactional
    public void delete(UUID userId, UUID simulationId) {
        FaceSimulation simulation = faceSimulationRepository.findOwnedForUpdate(simulationId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.FACE_SIMULATION_NOT_FOUND));
        List<UUID> mediaIds = outputRepository.findByUserIdAndSimulationId(userId, simulationId).stream()
                .map(FaceSimulationOutput::getMediaBlobId)
                .toList();
        UUID sourceMediaId = simulation.getSourceMediaId();
        faceSimulationRepository.delete(simulation);
        faceSimulationRepository.flush();
        for (UUID mediaId : mediaIds) {
            mediaService.delete(userId, mediaId);
        }
        try {
            mediaService.delete(userId, sourceMediaId);
        } catch (AuthException e) {
            if (e.getErrorCode() != ErrorCode.MEDIA_IN_USE) {
                throw e;
            }
        }
    }

    public boolean processOneDue() {
        ClaimedSimulation claimed = inTransaction(this::claimNext);
        if (claimed == null) {
            return false;
        }
        try {
            FaceSimulationImageGenerator.FaceGenerationResult result = imageGenerator.generate(
                    new FaceSimulationImageGenerator.InputImage(
                            claimed.sourceMetadata().contentType(),
                            claimed.sourceDownload().content()
                    ),
                    claimed.trendDescription()
            );
            inTransaction(() -> complete(claimed.id(), result));
        } catch (FaceGenerationException e) {
            inTransaction(() -> fail(claimed.id(), e.reason().name()));
        } catch (RuntimeException e) {
            inTransaction(() -> fail(claimed.id(), FaceGenerationException.Reason.upstream_failure.name()));
        }
        return true;
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

    @Transactional
    public void deleteFaceMedia(UUID userId, UUID mediaId) {
        MediaMetadata metadata = mediaService.metadata(userId, mediaId);
        requireFacePurpose(metadata);
        if (metadata.purpose() == MediaBlob.Purpose.face_input) {
            deleteSimulationsUsingSource(userId, mediaId);
            mediaService.delete(userId, mediaId);
            return;
        }
        outputRepository.findByUserIdAndMediaBlobId(userId, mediaId).ifPresent(outputRepository::delete);
        outputRepository.flush();
        mediaService.delete(userId, mediaId);
    }

    @Transactional
    ClaimedSimulation claimNext() {
        FaceSimulation simulation = faceSimulationRepository.findFirstByStatusInOrderByCreatedAtAsc(DUE_STATUSES)
                .orElse(null);
        if (simulation == null) {
            return null;
        }
        if (!isProcessable(simulation)) {
            simulation.markCancelled(Instant.now(clock));
            return null;
        }
        simulation.markProcessing(Instant.now(clock));
        MediaDownload download = mediaService.download(simulation.getUserId(), simulation.getSourceMediaId());
        return new ClaimedSimulation(
                simulation.getId(),
                simulation.getTrendDescription(),
                download.metadata(),
                new SourceBytes(download.metadata(), downloadBytes(download))
        );
    }

    @Transactional
    void complete(UUID simulationId, FaceSimulationImageGenerator.FaceGenerationResult result) {
        FaceSimulation simulation = faceSimulationRepository.findForUpdate(simulationId).orElse(null);
        if (simulation == null || simulation.getStatus() != FaceSimulation.Status.processing) {
            return;
        }
        if (!isProcessable(simulation)) {
            simulation.markCancelled(Instant.now(clock));
            return;
        }
        Map<FaceSimulationOutput.Label, FaceSimulationImageGenerator.GeneratedImage> images = exactOutputs(result);
        var current = mediaService.storeGeneratedUnique(
                simulation.getUserId(),
                GeneratedMediaStore.GeneratedPurpose.CURRENT,
                images.get(FaceSimulationOutput.Label.current).contentType(),
                images.get(FaceSimulationOutput.Label.current).content()
        );
        var improved = mediaService.storeGeneratedUnique(
                simulation.getUserId(),
                GeneratedMediaStore.GeneratedPurpose.IMPROVED,
                images.get(FaceSimulationOutput.Label.improved).contentType(),
                images.get(FaceSimulationOutput.Label.improved).content()
        );
        outputRepository.save(new FaceSimulationOutput(
                simulation.getId(),
                simulation.getUserId(),
                current.media().id(),
                FaceSimulationOutput.Label.current,
                result.modelVersion(),
                result.promptVersion()
        ));
        outputRepository.save(new FaceSimulationOutput(
                simulation.getId(),
                simulation.getUserId(),
                improved.media().id(),
                FaceSimulationOutput.Label.improved,
                result.modelVersion(),
                result.promptVersion()
        ));
        simulation.markDone(Instant.now(clock));
    }

    @Transactional
    void fail(UUID simulationId, String reason) {
        faceSimulationRepository.findForUpdate(simulationId)
                .filter(simulation -> simulation.getStatus() == FaceSimulation.Status.processing)
                .ifPresent(simulation -> simulation.markFailed(reason, Instant.now(clock)));
    }

    private boolean isProcessable(FaceSimulation simulation) {
        if (userRepository.findByIdForUpdate(simulation.getUserId()).isEmpty()) {
            return false;
        }
        try {
            consentGuard.requireGranted(simulation.getUserId(), Consent.Purpose.FACE_AI);
            requireOwnedFaceInput(simulation.getUserId(), simulation.getSourceMediaId());
            return true;
        } catch (AuthException e) {
            return false;
        }
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

    private Map<FaceSimulationOutput.Label, FaceSimulationImageGenerator.GeneratedImage> exactOutputs(
            FaceSimulationImageGenerator.FaceGenerationResult result
    ) {
        if (result == null || result.images() == null || result.images().size() != 2) {
            throw new FaceGenerationException(
                    FaceGenerationException.Reason.malformed_response,
                    "face generation must return exactly two images"
            );
        }
        Map<FaceSimulationOutput.Label, FaceSimulationImageGenerator.GeneratedImage> byLabel =
                new EnumMap<>(FaceSimulationOutput.Label.class);
        for (var image : result.images()) {
            if (image.label() == null || byLabel.put(image.label(), image) != null) {
                throw new FaceGenerationException(
                        FaceGenerationException.Reason.malformed_response,
                        "face generation labels must be current and improved"
                );
            }
        }
        if (!byLabel.keySet().containsAll(List.of(
                FaceSimulationOutput.Label.current,
                FaceSimulationOutput.Label.improved
        ))) {
            throw new FaceGenerationException(
                    FaceGenerationException.Reason.malformed_response,
                    "face generation labels must be current and improved"
            );
        }
        return byLabel;
    }

    private void requireFacePurpose(MediaMetadata metadata) {
        if (metadata.purpose() != MediaBlob.Purpose.face_input
                && metadata.purpose() != MediaBlob.Purpose.face_output_current
                && metadata.purpose() != MediaBlob.Purpose.face_output_improved) {
            throw new AuthException(ErrorCode.MEDIA_NOT_FOUND);
        }
    }

    private SimulationResponse toResponse(FaceSimulation simulation, boolean replayed) {
        return SimulationResponse.from(
                simulation,
                replayed,
                outputRepository.findByUserIdAndSimulationId(simulation.getUserId(), simulation.getId())
        );
    }

    private void deleteSimulationsUsingSource(UUID userId, UUID sourceMediaId) {
        List<FaceSimulation> simulations = faceSimulationRepository.findByUserIdAndSourceMediaId(userId, sourceMediaId);
        List<UUID> outputMediaIds = simulations.stream()
                .flatMap(simulation -> outputRepository.findByUserIdAndSimulationId(userId, simulation.getId()).stream())
                .map(FaceSimulationOutput::getMediaBlobId)
                .toList();
        faceSimulationRepository.deleteAll(simulations);
        faceSimulationRepository.flush();
        for (UUID outputMediaId : outputMediaIds) {
            mediaService.delete(userId, outputMediaId);
        }
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

    private byte[] downloadBytes(MediaDownload download) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            download.writeTo(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read private media bytes", e);
        }
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

    private <T> T inTransaction(Supplier<T> action) {
        TransactionTemplate template = transactionTemplate.getIfAvailable();
        if (template == null) {
            return action.get();
        }
        return template.execute(status -> action.get());
    }

    private void inTransaction(Runnable action) {
        TransactionTemplate template = transactionTemplate.getIfAvailable();
        if (template == null) {
            action.run();
            return;
        }
        template.executeWithoutResult(status -> action.run());
    }

    record ClaimedSimulation(
            UUID id,
            String trendDescription,
            MediaMetadata sourceMetadata,
            SourceBytes sourceDownload
    ) {
    }

    record SourceBytes(MediaMetadata metadata, byte[] content) {
    }
}
