package org.example.naeilbank.domain.media;

import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.example.naeilbank.domain.model.repository.MediaBlobRepository;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.config.properties.UploadProperties;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {
    @Mock
    MediaBlobRepository mediaBlobRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ConsentGuard consentGuard;

    MediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaService = service(DataSize.ofMegabytes(10), DataSize.ofMegabytes(20));
    }

    @Test
    void uploadLocksUserRequiresPurposeConsentAndPersistsValidatedBytes() {
        UUID userId = UUID.randomUUID();
        allowUser(userId);
        when(mediaBlobRepository.findMetadataByDigest(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(mediaBlobRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var stored = mediaService.storeUpload(
                userId,
                MediaUploadPurpose.MEAL_INPUT,
                multipart(ImageTestFixtures.png(20, 10), "image/png")
        );

        assertThat(stored.deduplicated()).isFalse();
        assertThat(stored.media().contentType()).isEqualTo("image/png");
        verify(consentGuard).requireGranted(userId, Consent.Purpose.MEAL_AI);
        ArgumentCaptor<MediaBlob> saved = ArgumentCaptor.forClass(MediaBlob.class);
        verify(mediaBlobRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPurpose()).isEqualTo(MediaBlob.Purpose.meal_input);
    }

    @Test
    void sameTenantPurposeHashReturnsExistingWithoutDuplicateWrite() {
        UUID userId = UUID.randomUUID();
        allowUser(userId);
        byte[] content = ImageTestFixtures.webp();
        MediaBlobMetadataViewStub existing = new MediaBlobMetadataViewStub(
                UUID.randomUUID(), MediaBlob.Purpose.face_input, "image/webp", content.length,
                "a".repeat(64)
        );
        when(mediaBlobRepository.findMetadataByDigest(
                any(), any(), any(), any())).thenReturn(Optional.of(existing));

        var stored = mediaService.storeUpload(
                userId,
                MediaUploadPurpose.FACE_INPUT,
                multipart(content, "image/webp")
        );

        assertThat(stored.deduplicated()).isTrue();
        verify(consentGuard).requireGranted(userId, Consent.Purpose.FACE_AI);
        verify(mediaBlobRepository, never()).saveAndFlush(any());
    }

    @Test
    void inputAndGeneratedLimitsAreEnforcedBeforePersistence() {
        UUID userId = UUID.randomUUID();
        allowUser(userId);
        mediaService = service(DataSize.ofBytes(10), DataSize.ofBytes(20));

        assertError(() -> mediaService.storeUpload(
                userId,
                MediaUploadPurpose.MEAL_INPUT,
                multipart(new byte[11], "image/png")
        ), ErrorCode.MEDIA_TOO_LARGE);
        assertError(() -> mediaService.storeGenerated(
                userId,
                GeneratedMediaStore.GeneratedPurpose.CURRENT,
                "image/png",
                ImageTestFixtures.png(1, 1)
        ), ErrorCode.MEDIA_TOO_LARGE);
        verify(mediaBlobRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingOwnerReadAndReferencedDeleteUseTypedErrors() {
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(mediaBlobRepository.findMetadata(
                mediaId, userId, MediaBlob.Status.active)).thenReturn(Optional.empty());
        assertError(() -> mediaService.metadata(userId, mediaId), ErrorCode.MEDIA_NOT_FOUND);

        MediaBlob existing = new MediaBlob(
                userId,
                MediaBlob.Purpose.meal_input,
                "image/png",
                "a".repeat(64),
                ImageTestFixtures.png(1, 1)
        );
        when(mediaBlobRepository.findByIdAndUserIdAndStatus(
                mediaId, userId, MediaBlob.Status.active)).thenReturn(Optional.of(existing));
        doThrow(new DataIntegrityViolationException("referenced"))
                .when(mediaBlobRepository).flush();

        assertError(() -> mediaService.delete(userId, mediaId), ErrorCode.MEDIA_IN_USE);
    }

    private MediaService service(DataSize inputLimit, DataSize generatedLimit) {
        return new MediaService(
                mediaBlobRepository,
                userRepository,
                consentGuard,
                new ImageValidator(new BoundedImageDecoder()),
                new UploadProperties(inputLimit, DataSize.ofMegabytes(11), generatedLimit)
        );
    }

    private void allowUser(UUID userId) {
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(
                User.local("media-" + userId + "@example.com", "hash")
        ));
    }

    private MockMultipartFile multipart(byte[] content, String contentType) {
        return new MockMultipartFile("file", "ignored-name", contentType, content);
    }

    private void assertError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(expected);
    }

    private record MediaBlobMetadataViewStub(
            UUID id,
            MediaBlob.Purpose purpose,
            String contentType,
            long sizeBytes,
            String sha256
    ) implements org.example.naeilbank.domain.model.repository.MediaBlobMetadataView {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public MediaBlob.Purpose getPurpose() {
            return purpose;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public long getSizeBytes() {
            return sizeBytes;
        }

        @Override
        public String getSha256() {
            return sha256;
        }

        @Override
        public java.time.Instant getCreatedAt() {
            return java.time.Instant.parse("2026-01-01T00:00:00Z");
        }
    }
}
