package org.example.naeilbank.domain.face;

import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.media.GeneratedMediaStore;
import org.example.naeilbank.domain.media.MediaModels;
import org.example.naeilbank.domain.media.MediaService;
import org.example.naeilbank.domain.model.entity.FaceSimulation;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.domain.model.entity.MediaBlob;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceSimulationServiceTest {
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
    ConsentGuard consentGuard;
    @Mock
    UserRepository userRepository;
    @Mock
    FaceSimulationImageGenerator imageGenerator;
    @Mock
    ObjectProvider<org.springframework.transaction.support.TransactionTemplate> transactionTemplate;

    FaceSimulationService service;

    @BeforeEach
    void setUp() {
        service = new FaceSimulationService(
                simulationRepository,
                outputRepository,
                mediaBlobRepository,
                mediaService,
                consentGuard,
                userRepository,
                imageGenerator,
                CLOCK,
                transactionTemplate
        );
        when(transactionTemplate.getIfAvailable()).thenReturn(null);
    }

    @Test
    void processStoresExactlyTwoOutputsWithLabelsAndVersions() {
        UUID userId = UUID.randomUUID();
        UUID simulationId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        FaceSimulation simulation = simulation(userId, simulationId, sourceMediaId);
        when(simulationRepository.findFirstByStatusInOrderByCreatedAtAsc(any()))
                .thenReturn(Optional.of(simulation));
        allowProcessable(userId, sourceMediaId);
        when(mediaService.download(userId, sourceMediaId)).thenReturn(download(sourceMediaId));
        when(imageGenerator.generate(any(), any())).thenReturn(new FaceSimulationImageGenerator.FaceGenerationResult(
                "model-v1",
                "prompt-v1",
                List.of(
                        new FaceSimulationImageGenerator.GeneratedImage(
                                FaceSimulationOutput.Label.current, "image/png", BYTES),
                        new FaceSimulationImageGenerator.GeneratedImage(
                                FaceSimulationOutput.Label.improved, "image/png", BYTES)
                )
        ));
        when(simulationRepository.findForUpdate(simulationId)).thenReturn(Optional.of(simulation));
        UUID currentMediaId = UUID.randomUUID();
        UUID improvedMediaId = UUID.randomUUID();
        when(mediaService.storeGeneratedUnique(userId, GeneratedMediaStore.GeneratedPurpose.CURRENT, "image/png", BYTES))
                .thenReturn(stored(currentMediaId, MediaBlob.Purpose.face_output_current));
        when(mediaService.storeGeneratedUnique(userId, GeneratedMediaStore.GeneratedPurpose.IMPROVED, "image/png", BYTES))
                .thenReturn(stored(improvedMediaId, MediaBlob.Purpose.face_output_improved));

        boolean processed = service.processOneDue();

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
        when(simulationRepository.findFirstByStatusInOrderByCreatedAtAsc(any()))
                .thenReturn(Optional.of(simulation));
        allowProcessable(userId, sourceMediaId);
        when(mediaService.download(userId, sourceMediaId)).thenReturn(download(sourceMediaId));
        when(imageGenerator.generate(any(), any())).thenReturn(new FaceSimulationImageGenerator.FaceGenerationResult(
                "model-v1",
                "prompt-v1",
                List.of(new FaceSimulationImageGenerator.GeneratedImage(
                        FaceSimulationOutput.Label.current, "image/png", BYTES))
        ));
        when(simulationRepository.findForUpdate(simulationId)).thenReturn(Optional.of(simulation));

        boolean processed = service.processOneDue();

        assertThat(processed).isTrue();
        assertThat(simulation.getStatus()).isEqualTo(FaceSimulation.Status.failed);
        assertThat(simulation.getFailureReason()).isEqualTo("malformed_response");
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
