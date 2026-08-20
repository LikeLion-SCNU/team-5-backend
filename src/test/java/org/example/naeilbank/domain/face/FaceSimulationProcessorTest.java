package org.example.naeilbank.domain.face;

import org.example.naeilbank.domain.media.GeneratedMediaStore;
import org.example.naeilbank.domain.media.MediaModels;
import org.example.naeilbank.domain.media.MediaService;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.entity.FaceSimulation;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.example.naeilbank.domain.model.repository.ConsentRepository;
import org.example.naeilbank.domain.model.repository.FaceSimulationOutputRepository;
import org.example.naeilbank.domain.model.repository.FaceSimulationRepository;
import org.example.naeilbank.domain.model.repository.MediaBlobRepository;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceSimulationProcessorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
    private static final byte[] BYTES = {1, 2, 3};

    @Mock
    FaceSimulationRepository simulationRepository;
    @Mock
    FaceSimulationOutputRepository outputRepository;
    @Mock
    MediaBlobRepository mediaBlobRepository;
    @Mock
    MediaService mediaService;
    @Mock
    ConsentRepository consentRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    FaceSimulationImageGenerator imageGenerator;
    @Mock
    TransactionTemplate transactionTemplate;

    FaceSimulationProcessor processor;
    FaceSimulationGenerationService generationService;

    @BeforeEach
    void setUp() {
        generationService = new FaceSimulationGenerationService(
                org.mockito.Mockito.mock(org.example.naeilbank.domain.audit.AuditAppendService.class),
                simulationRepository,
                outputRepository,
                mediaBlobRepository,
                mediaService,
                consentRepository,
                userRepository,
                imageGenerator,
                new FaceSimulationInterlock(),
                CLOCK,
                transactionTemplate
        );
        processor = new FaceSimulationProcessor(
                simulationRepository,
                userRepository,
                generationService,
                CLOCK,
                transactionTemplate
        );
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void claimsQueuedOrStaleProcessingWorkUsingFixedRecoveryCutoff() {
        Instant staleBefore = Instant.parse("2026-08-19T23:45:00Z");
        when(simulationRepository.findNextDueId(CLOCK.instant(), staleBefore)).thenReturn(Optional.empty());

        assertThat(processor.processOneDue()).isFalse();

        verify(simulationRepository).findNextDueId(CLOCK.instant(), staleBefore);
    }

    @Test
    void processStoresExactlyTwoOutputsWithLabelsAndVersions() {
        UUID userId = UUID.randomUUID();
        UUID simulationId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        FaceSimulation simulation = simulation(userId, simulationId, sourceMediaId);
        allowClaim(simulation);
        allowProcessable(userId, sourceMediaId);
        when(mediaService.download(userId, sourceMediaId)).thenReturn(download(sourceMediaId));
        when(imageGenerator.generate(any(), any(), any())).thenAnswer(invocation -> generated(invocation.getArgument(2)));
        UUID currentMediaId = UUID.randomUUID();
        UUID improvedMediaId = UUID.randomUUID();
        when(mediaService.storeGeneratedUnique(userId, GeneratedMediaStore.GeneratedPurpose.CURRENT, "image/png", BYTES))
                .thenReturn(stored(currentMediaId, MediaBlob.Purpose.face_output_current));
        when(mediaService.storeGeneratedUnique(userId, GeneratedMediaStore.GeneratedPurpose.IMPROVED, "image/png", BYTES))
                .thenReturn(stored(improvedMediaId, MediaBlob.Purpose.face_output_improved));

        boolean processed = processor.processOneDue();

        assertThat(processed).isTrue();
        assertThat(simulation.getStatus()).isEqualTo(FaceSimulation.Status.done);
        ArgumentCaptor<FaceSimulationOutput> output = ArgumentCaptor.forClass(FaceSimulationOutput.class);
        verify(outputRepository, org.mockito.Mockito.times(2)).save(output.capture());
        assertThat(output.getAllValues()).extracting(FaceSimulationOutput::getLabel)
                .containsExactly(FaceSimulationOutput.Label.current, FaceSimulationOutput.Label.improved);
        assertThat(output.getAllValues()).allSatisfy(saved -> {
            assertThat(saved.getModelVersion()).isEqualTo("model-v1");
            assertThat(saved.getPromptVersion()).isEqualTo("prompt-v1");
        });
    }

    @Test
    void oneReturnedImageFailsWholeSimulationWithoutPersistingGeneratedMedia() {
        UUID userId = UUID.randomUUID();
        UUID simulationId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        FaceSimulation simulation = simulation(userId, simulationId, sourceMediaId);
        allowClaim(simulation);
        allowProcessable(userId, sourceMediaId);
        when(mediaService.download(userId, sourceMediaId)).thenReturn(download(sourceMediaId));
        when(imageGenerator.generate(any(), any(), any())).thenAnswer(invocation -> {
            FaceSimulationOutput.Label label = invocation.getArgument(2);
            if (label == FaceSimulationOutput.Label.current) {
                return generated(label);
            }
            return new FaceSimulationImageGenerator.FaceGenerationResult("model-v1", "prompt-v1", List.of());
        });

        boolean processed = processor.processOneDue();

        assertThat(processed).isTrue();
        assertThat(simulation.getStatus()).isEqualTo(FaceSimulation.Status.failed);
        assertThat(simulation.getFailureReason()).isEqualTo("malformed_response");
        verify(mediaService, never()).storeGeneratedUnique(any(), any(), any(), any());
        verify(outputRepository, never()).save(any());
    }

    @Test
    void cancellationDuringVendorCallDiscardsBothGeneratedOutputs() {
        UUID userId = UUID.randomUUID();
        UUID simulationId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        FaceSimulation simulation = simulation(userId, simulationId, sourceMediaId);
        allowClaim(simulation);
        allowProcessable(userId, sourceMediaId);
        when(mediaService.download(userId, sourceMediaId)).thenReturn(download(sourceMediaId));
        when(imageGenerator.generate(any(), any(), any())).thenAnswer(invocation -> {
            simulation.markCancelled(CLOCK.instant());
            return generated(invocation.getArgument(2));
        });

        assertThat(processor.processOneDue()).isTrue();

        assertThat(simulation.getStatus()).isEqualTo(FaceSimulation.Status.cancelled);
        verify(mediaService, never()).storeGeneratedUnique(any(), any(), any(), any());
        verify(outputRepository, never()).save(any());
    }

    private FaceSimulation simulation(UUID userId, UUID simulationId, UUID sourceMediaId) {
        FaceSimulation simulation = new FaceSimulation(
                userId,
                sourceMediaId,
                "trend",
                "key-" + simulationId,
                "hash-" + simulationId
        );
        ReflectionTestUtils.setField(simulation, "id", simulationId);
        ReflectionTestUtils.setField(simulation, "nextAttemptAt", CLOCK.instant());
        return simulation;
    }

    private void allowProcessable(UUID userId, UUID sourceMediaId) {
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(
                User.local("face-" + userId + "@example.com", "hash")
        ));
        when(mediaBlobRepository.findByIdAndUserIdAndPurposeAndStatus(
                sourceMediaId,
                userId,
                MediaBlob.Purpose.face_input,
                MediaBlob.Status.active
        )).thenReturn(Optional.of(new MediaBlob(
                userId,
                MediaBlob.Purpose.face_input,
                "image/png",
                "a".repeat(64),
                BYTES
        )));
        when(consentRepository.findForUpdate(userId, Consent.Purpose.FACE_AI)).thenReturn(Optional.of(
                Consent.create(userId, Consent.Purpose.FACE_AI, true, 1, "f".repeat(64), CLOCK.instant())
        ));
    }

    private void allowClaim(FaceSimulation simulation) {
        UUID simulationId = simulation.getId();
        UUID userId = simulation.getUserId();
        when(simulationRepository.findNextDueId(any(), any())).thenReturn(Optional.of(simulationId));
        when(simulationRepository.findById(simulationId)).thenReturn(Optional.of(simulation));
        when(simulationRepository.findOwnedForUpdate(simulationId, userId)).thenReturn(Optional.of(simulation));
    }

    private FaceSimulationImageGenerator.FaceGenerationResult generated(FaceSimulationOutput.Label label) {
        return new FaceSimulationImageGenerator.FaceGenerationResult(
                "model-v1",
                "prompt-v1",
                List.of(new FaceSimulationImageGenerator.GeneratedImage(label, "image/png", BYTES))
        );
    }

    private MediaModels.MediaDownload download(UUID mediaId) {
        return new MediaModels.MediaDownload(
                new MediaModels.MediaMetadata(
                        mediaId,
                        MediaBlob.Purpose.face_input,
                        "image/png",
                        BYTES.length,
                        "\"etag\"",
                        Instant.now(CLOCK)
                ),
                BYTES
        );
    }

    private MediaModels.StoredMedia stored(UUID mediaId, MediaBlob.Purpose purpose) {
        return new MediaModels.StoredMedia(
                new MediaModels.MediaMetadata(
                        mediaId,
                        purpose,
                        "image/png",
                        BYTES.length,
                        "\"etag-" + mediaId + "\"",
                        Instant.now(CLOCK)
                ),
                false
        );
    }
}
