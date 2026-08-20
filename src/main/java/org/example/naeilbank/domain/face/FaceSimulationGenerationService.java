package org.example.naeilbank.domain.face;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.media.GeneratedMediaStore;
import org.example.naeilbank.domain.media.MediaModels.MediaDownload;
import org.example.naeilbank.domain.media.MediaService;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.entity.FaceSimulation;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.example.naeilbank.domain.model.repository.ConsentRepository;
import org.example.naeilbank.domain.model.repository.FaceSimulationOutputRepository;
import org.example.naeilbank.domain.model.repository.FaceSimulationRepository;
import org.example.naeilbank.domain.model.repository.MediaBlobRepository;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class FaceSimulationGenerationService {
    private final FaceSimulationRepository simulationRepository;
    private final FaceSimulationOutputRepository outputRepository;
    private final MediaBlobRepository mediaBlobRepository;
    private final MediaService mediaService;
    private final ConsentRepository consentRepository;
    private final UserRepository userRepository;
    private final FaceSimulationImageGenerator imageGenerator;
    private final FaceSimulationInterlock interlock;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    void process(FaceSimulationProcessor.ClaimedSimulation claimed) {
        if (interlock.isCancellationRequested(claimed.id(), claimed.userId())) {
            return;
        }
        var current = generateOne(claimed, FaceSimulationOutput.Label.current);
        if (current == null || interlock.isCancellationRequested(claimed.id(), claimed.userId())) {
            return;
        }
        var improved = generateOne(claimed, FaceSimulationOutput.Label.improved);
        if (improved == null) {
            return;
        }
        inTransaction(() -> completeClaim(claimed, current, improved));
    }

    private FaceSimulationImageGenerator.FaceGenerationResult generateOne(
            FaceSimulationProcessor.ClaimedSimulation claimed,
            FaceSimulationOutput.Label requestedLabel
    ) {
        if (interlock.isCancellationRequested(claimed.id(), claimed.userId())) {
            return null;
        }
        FaceSimulationImageGenerator.InputImage input = inTransaction(() -> prepareInput(claimed));
        if (input == null || interlock.isCancellationRequested(claimed.id(), claimed.userId())) {
            return null;
        }
        return imageGenerator.generate(
                input,
                claimed.trendDescription(),
                requestedLabel
        );
    }

    private FaceSimulationImageGenerator.InputImage prepareInput(
            FaceSimulationProcessor.ClaimedSimulation claimed
    ) {
        if (lockProcessable(claimed) == null) {
            return null;
        }
        MediaDownload source = mediaService.download(claimed.userId(), claimed.sourceMediaId());
        return new FaceSimulationImageGenerator.InputImage(
                source.metadata().contentType(),
                downloadBytes(source)
        );
    }

    private void completeClaim(
            FaceSimulationProcessor.ClaimedSimulation claimed,
            FaceSimulationImageGenerator.FaceGenerationResult currentResult,
            FaceSimulationImageGenerator.FaceGenerationResult improvedResult
    ) {
        Map<FaceSimulationOutput.Label, FaceSimulationImageGenerator.GeneratedImage> images = exactOutputs(
                currentResult,
                improvedResult
        );
        FaceSimulation simulation = lockProcessable(claimed);
        if (simulation == null) {
            return;
        }
        var current = mediaService.storeGeneratedUnique(
                claimed.userId(),
                GeneratedMediaStore.GeneratedPurpose.CURRENT,
                images.get(FaceSimulationOutput.Label.current).contentType(),
                images.get(FaceSimulationOutput.Label.current).content()
        );
        var improved = mediaService.storeGeneratedUnique(
                claimed.userId(),
                GeneratedMediaStore.GeneratedPurpose.IMPROVED,
                images.get(FaceSimulationOutput.Label.improved).contentType(),
                images.get(FaceSimulationOutput.Label.improved).content()
        );
        outputRepository.save(new FaceSimulationOutput(
                claimed.id(), claimed.userId(), current.media().id(),
                FaceSimulationOutput.Label.current,
                currentResult.modelVersion(),
                currentResult.promptVersion()
        ));
        outputRepository.save(new FaceSimulationOutput(
                claimed.id(), claimed.userId(), improved.media().id(),
                FaceSimulationOutput.Label.improved,
                improvedResult.modelVersion(),
                improvedResult.promptVersion()
        ));
        simulation.markDone(Instant.now(clock));
    }

    private FaceSimulation lockProcessable(FaceSimulationProcessor.ClaimedSimulation claimed) {
        if (interlock.isCancellationRequested(claimed.id(), claimed.userId())) {
            return null;
        }
        if (userRepository.findByIdForUpdate(claimed.userId()).isEmpty()) {
            return null;
        }
        FaceSimulation simulation = simulationRepository
                .findOwnedForUpdate(claimed.id(), claimed.userId())
                .orElse(null);
        if (simulation == null || !simulation.matchesClaim(claimed.claimToken())) {
            return null;
        }
        Consent faceConsent = consentRepository
                .findForUpdate(claimed.userId(), Consent.Purpose.FACE_AI)
                .orElse(null);
        if (faceConsent == null || !faceConsent.isGranted() || !hasActiveSource(claimed)) {
            simulation.markCancelled(Instant.now(clock));
            return null;
        }
        return simulation;
    }

    private boolean hasActiveSource(FaceSimulationProcessor.ClaimedSimulation claimed) {
        return mediaBlobRepository.findByIdAndUserIdAndPurposeAndStatus(
                claimed.sourceMediaId(),
                claimed.userId(),
                MediaBlob.Purpose.face_input,
                MediaBlob.Status.active
        ).isPresent();
    }

    private Map<FaceSimulationOutput.Label, FaceSimulationImageGenerator.GeneratedImage> exactOutputs(
            FaceSimulationImageGenerator.FaceGenerationResult currentResult,
            FaceSimulationImageGenerator.FaceGenerationResult improvedResult
    ) {
        if (!sameVersion(currentResult, improvedResult)) {
            throw malformedOutputs();
        }
        Map<FaceSimulationOutput.Label, FaceSimulationImageGenerator.GeneratedImage> byLabel =
                new EnumMap<>(FaceSimulationOutput.Label.class);
        for (var result : List.of(currentResult, improvedResult)) {
            if (result.images() == null || result.images().size() != 1) {
                throw malformedOutputs();
            }
            var image = result.images().getFirst();
            if (image == null || image.label() == null || byLabel.put(image.label(), image) != null) {
                throw malformedOutputs();
            }
        }
        if (!byLabel.keySet().containsAll(List.of(
                FaceSimulationOutput.Label.current,
                FaceSimulationOutput.Label.improved))) {
            throw malformedOutputs();
        }
        return byLabel;
    }

    private boolean sameVersion(
            FaceSimulationImageGenerator.FaceGenerationResult currentResult,
            FaceSimulationImageGenerator.FaceGenerationResult improvedResult
    ) {
        return currentResult != null
                && improvedResult != null
                && Objects.equals(currentResult.modelVersion(), improvedResult.modelVersion())
                && Objects.equals(currentResult.promptVersion(), improvedResult.promptVersion());
    }

    private FaceGenerationException malformedOutputs() {
        return new FaceGenerationException(
                FaceGenerationException.Reason.malformed_response,
                "face generation must return matching current and improved images"
        );
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

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private void inTransaction(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }
}
