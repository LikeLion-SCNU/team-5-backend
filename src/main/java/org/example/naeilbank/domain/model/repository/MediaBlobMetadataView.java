package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.MediaBlob;

import java.time.Instant;
import java.util.UUID;

public interface MediaBlobMetadataView {
    UUID getId();

    MediaBlob.Purpose getPurpose();

    String getContentType();

    long getSizeBytes();

    String getSha256();

    Instant getCreatedAt();
}
