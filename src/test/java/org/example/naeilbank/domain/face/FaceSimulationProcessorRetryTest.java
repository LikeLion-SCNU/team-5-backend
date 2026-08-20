package org.example.naeilbank.domain.face;

import org.example.naeilbank.domain.media.MediaModels;
import org.example.naeilbank.domain.media.MediaService;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.entity.FaceSimulation;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceSimulationProcessorRetryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
    private static final byte[] IMAGE = {1, 2, 3};

    @Mock FaceSimulationRepository simulationRepository;
    @Mock FaceSimulationOutputRepository outputRepository;
    @Mock MediaBlobRepository mediaBlobRepository;
    @Mock MediaService mediaService;
    @Mock ConsentRepository consentRepository;
    @Mock UserRepository userRepository;
    @Mock FaceSimulationImageGenerator imageGenerator;
    @Mock TransactionTemplate transactionTemplate;

    FaceSimulationProcessor processor;
    FaceSimulationGenerationService generationService;

    @BeforeEach
    void setUp() {
        generationService = new FaceSimulationGenerationService(
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
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
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
    void timeoutSchedulesBoundedBackoffInsteadOfTerminalFailure() {
        FaceSimulation simulation = dueSimulation();
        allowClaim(simulation);
        allowDispatch(simulation, true);
        when(imageGenerator.generate(any(), any(), any())).thenThrow(new FaceGenerationException(
                FaceGenerationException.Reason.timeout,
                "timeout"
        ));

        assertThat(processor.processOneDue()).isTrue();

        assertThat(simulation.getStatus()).isEqualTo(FaceSimulation.Status.queued);
        assertThat(simulation.getAttemptCount()).isOne();
        assertThat(simulation.getFailureReason()).isEqualTo("timeout");
        assertThat(simulation.getNextAttemptAt()).isEqualTo(CLOCK.instant().plusSeconds(60));
        verify(outputRepository, never()).save(any());
    }

    @Test
    void consentWithdrawnAfterClaimCancelsBeforeVendorDispatch() {
        FaceSimulation simulation = dueSimulation();
        allowClaim(simulation);
        when(consentRepository.findForUpdate(simulation.getUserId(), Consent.Purpose.FACE_AI))
                .thenReturn(Optional.of(Consent.create(
                        simulation.getUserId(),
                        Consent.Purpose.FACE_AI,
                        false,
                        1,
                        "f".repeat(64),
                        CLOCK.instant()
                )));

        FaceSimulationProcessor.ClaimedSimulation claim = processor.claimNext();
        generationService.process(claim);

        assertThat(simulation.getStatus()).isEqualTo(FaceSimulation.Status.cancelled);
        verify(imageGenerator, never()).generate(any(), any(), any());
    }

    @Test
    void exhaustedAttemptBudgetBecomesTerminalWithoutAnotherVendorCall() {
        FaceSimulation simulation = dueSimulation();
        ReflectionTestUtils.setField(simulation, "attemptCount", 3);
        allowClaim(simulation);

        assertThat(processor.claimNext()).isNull();

        assertThat(simulation.getStatus()).isEqualTo(FaceSimulation.Status.failed);
        assertThat(simulation.getFailureReason()).isEqualTo("retry_exhausted");
        verify(imageGenerator, never()).generate(any(), any(), any());
    }

    @Test
    void authenticationFailureIsTerminalWithoutRetry() {
        assertTerminalFailure(FaceGenerationException.Reason.authentication_failed);
    }

    @Test
    void safetyRefusalIsTerminalWithoutRetry() {
        assertTerminalFailure(FaceGenerationException.Reason.safety_refusal);
    }

    private void assertTerminalFailure(FaceGenerationException.Reason reason) {
        FaceSimulation simulation = dueSimulation();
        allowClaim(simulation);
        allowDispatch(simulation, true);
        when(imageGenerator.generate(any(), any(), any())).thenThrow(new FaceGenerationException(reason, reason.name()));

        assertThat(processor.processOneDue()).isTrue();

        assertThat(simulation.getStatus()).isEqualTo(FaceSimulation.Status.failed);
        assertThat(simulation.getFailureReason()).isEqualTo(reason.name());
        assertThat(simulation.getNextAttemptAt()).isEqualTo(CLOCK.instant());
        verify(outputRepository, never()).save(any());
    }

    private FaceSimulation dueSimulation() {
        UUID id = UUID.randomUUID();
        FaceSimulation simulation = new FaceSimulation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "trend",
                "key-" + id,
                "hash-" + id
        );
        ReflectionTestUtils.setField(simulation, "id", id);
        ReflectionTestUtils.setField(simulation, "nextAttemptAt", CLOCK.instant());
        return simulation;
    }

    private void allowClaim(FaceSimulation simulation) {
        User user = User.local("face-" + simulation.getUserId() + "@example.com", "hash");
        when(simulationRepository.findNextDueId(any(), any())).thenReturn(Optional.of(simulation.getId()));
        when(simulationRepository.findById(simulation.getId())).thenReturn(Optional.of(simulation));
        when(userRepository.findByIdForUpdate(simulation.getUserId())).thenReturn(Optional.of(user));
        when(simulationRepository.findOwnedForUpdate(simulation.getId(), simulation.getUserId()))
                .thenReturn(Optional.of(simulation));
    }

    private void allowDispatch(FaceSimulation simulation, boolean granted) {
        when(consentRepository.findForUpdate(simulation.getUserId(), Consent.Purpose.FACE_AI))
                .thenReturn(Optional.of(Consent.create(
                        simulation.getUserId(),
                        Consent.Purpose.FACE_AI,
                        granted,
                        1,
                        "f".repeat(64),
                        CLOCK.instant()
                )));
        when(mediaBlobRepository.findByIdAndUserIdAndPurposeAndStatus(
                simulation.getSourceMediaId(),
                simulation.getUserId(),
                MediaBlob.Purpose.face_input,
                MediaBlob.Status.active
        )).thenReturn(Optional.of(new MediaBlob(
                simulation.getUserId(),
                MediaBlob.Purpose.face_input,
                "image/png",
                "a".repeat(64),
                IMAGE
        )));
        when(mediaService.download(simulation.getUserId(), simulation.getSourceMediaId()))
                .thenReturn(new MediaModels.MediaDownload(
                        new MediaModels.MediaMetadata(
                                simulation.getSourceMediaId(),
                                MediaBlob.Purpose.face_input,
                                "image/png",
                                IMAGE.length,
                                "\"etag\"",
                                CLOCK.instant()
                        ),
                        IMAGE
                ));
    }
}
