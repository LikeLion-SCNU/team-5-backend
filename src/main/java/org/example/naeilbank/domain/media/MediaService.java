package org.example.naeilbank.domain.media;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.media.MediaModels.MediaDownload;
import org.example.naeilbank.domain.media.MediaModels.MediaMetadata;
import org.example.naeilbank.domain.media.MediaModels.StoredMedia;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.example.naeilbank.domain.model.repository.MediaBlobRepository;
import org.example.naeilbank.global.config.properties.UploadProperties;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaService implements GeneratedMediaStore {
    private static final int BUFFER_SIZE = 8192;

    private final MediaBlobRepository mediaBlobRepository;
    private final UserRepository userRepository;
    private final ConsentGuard consentGuard;
    private final ImageValidator imageValidator;
    private final UploadProperties uploadProperties;

    @Transactional
    public StoredMedia storeUpload(UUID userId, MediaUploadPurpose purpose, MultipartFile file) {
        lockUser(userId);
        consentGuard.requireGranted(userId, purpose.requiredConsent());
        long maxBytes = uploadProperties.maxInputSize().toBytes();
        byte[] content = readBounded(file, maxBytes);
        ImageValidator.ValidatedImage image = imageValidator.validate(
                content,
                file.getContentType(),
                maxBytes
        );
        return store(userId, purpose.storedPurpose(), image.contentType(), content);
    }

    @Override
    @Transactional
    public StoredMedia storeGenerated(
            UUID userId,
            GeneratedPurpose purpose,
            String contentType,
            byte[] content
    ) {
        lockUser(userId);
        consentGuard.requireGranted(userId, Consent.Purpose.FACE_AI);
        long maxBytes = uploadProperties.maxGeneratedSize().toBytes();
        ImageValidator.ValidatedImage image = imageValidator.validate(content, contentType, maxBytes);
        return store(userId, purpose.storedPurpose(), image.contentType(), content);
    }

    @Transactional(readOnly = true)
    public MediaMetadata metadata(UUID userId, UUID mediaId) {
        return mediaBlobRepository.findMetadata(mediaId, userId, MediaBlob.Status.active)
                .map(MediaMetadata::from)
                .orElseThrow(() -> new AuthException(ErrorCode.MEDIA_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public MediaDownload download(UUID userId, UUID mediaId) {
        MediaBlob mediaBlob = findOwned(userId, mediaId);
        byte[] content = mediaBlob.getContent();
        if (content.length != mediaBlob.getSizeBytes()
                || content.length > uploadProperties.maxGeneratedSize().toBytes()) {
            throw new IllegalStateException("Stored media violates the bounded content contract");
        }
        return new MediaDownload(MediaMetadata.from(mediaBlob), content);
    }

    @Transactional
    public void delete(UUID userId, UUID mediaId) {
        MediaBlob mediaBlob = findOwned(userId, mediaId);
        try {
            mediaBlobRepository.delete(mediaBlob);
            mediaBlobRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(ErrorCode.MEDIA_IN_USE);
        }
    }

    private StoredMedia store(
            UUID userId,
            MediaBlob.Purpose purpose,
            String contentType,
            byte[] content
    ) {
        String sha256 = sha256(content);
        var existing = mediaBlobRepository.findMetadataByDigest(
                userId,
                purpose,
                sha256,
                MediaBlob.Status.active
        );
        if (existing.isPresent()) {
            return new StoredMedia(MediaMetadata.from(existing.get()), true);
        }
        MediaBlob saved = mediaBlobRepository.saveAndFlush(
                new MediaBlob(userId, purpose, contentType, sha256, content)
        );
        return new StoredMedia(MediaMetadata.from(saved), false);
    }

    private MediaBlob findOwned(UUID userId, UUID mediaId) {
        return mediaBlobRepository.findByIdAndUserIdAndStatus(
                        mediaId,
                        userId,
                        MediaBlob.Status.active
                )
                .orElseThrow(() -> new AuthException(ErrorCode.MEDIA_NOT_FOUND));
    }

    private void lockUser(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
    }

    private byte[] readBounded(MultipartFile file, long maxBytes) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new AuthException(ErrorCode.INVALID_IMAGE);
        }
        if (file.getSize() > maxBytes) {
            throw new AuthException(ErrorCode.MEDIA_TOO_LARGE);
        }
        int initialSize = Math.toIntExact(Math.min(file.getSize(), BUFFER_SIZE));
        try (InputStream input = file.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new AuthException(ErrorCode.MEDIA_TOO_LARGE);
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) {
                throw new AuthException(ErrorCode.INVALID_IMAGE);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bounded media upload", e);
        }
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
