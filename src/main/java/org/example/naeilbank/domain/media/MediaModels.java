package org.example.naeilbank.domain.media;

import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.example.naeilbank.domain.model.repository.MediaBlobMetadataView;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.UUID;

public final class MediaModels {
    private MediaModels() {
    }

    public record MediaMetadata(
            UUID id,
            MediaBlob.Purpose purpose,
            String contentType,
            long sizeBytes,
            String etag,
            Instant createdAt
    ) {
        public static MediaMetadata from(MediaBlob mediaBlob) {
            return new MediaMetadata(
                    mediaBlob.getId(),
                    mediaBlob.getPurpose(),
                    mediaBlob.getContentType(),
                    mediaBlob.getSizeBytes(),
                    quotedEtag(mediaBlob.getSha256()),
                    mediaBlob.getCreatedAt()
            );
        }

        public static MediaMetadata from(MediaBlobMetadataView mediaBlob) {
            return new MediaMetadata(
                    mediaBlob.getId(),
                    mediaBlob.getPurpose(),
                    mediaBlob.getContentType(),
                    mediaBlob.getSizeBytes(),
                    quotedEtag(mediaBlob.getSha256()),
                    mediaBlob.getCreatedAt()
            );
        }
    }

    public record StoredMedia(MediaMetadata media, boolean deduplicated) {
    }

    public static final class MediaDownload {
        private final MediaMetadata metadata;
        private final byte[] content;

        public MediaDownload(MediaMetadata metadata, byte[] content) {
            this.metadata = metadata;
            this.content = content;
        }

        public MediaMetadata metadata() {
            return metadata;
        }

        public void writeTo(OutputStream output) throws IOException {
            output.write(content);
        }
    }

    private static String quotedEtag(String sha256) {
        return "\"" + sha256 + "\"";
    }
}
