package org.example.naeilbank.domain.media;

import org.example.naeilbank.domain.model.entity.MediaBlob;

import java.util.UUID;

public interface GeneratedMediaStore {
    MediaModels.StoredMedia storeGenerated(
            UUID userId,
            GeneratedPurpose purpose,
            String contentType,
            byte[] content
    );

    enum GeneratedPurpose {
        CURRENT(MediaBlob.Purpose.face_output_current),
        IMPROVED(MediaBlob.Purpose.face_output_improved);

        private final MediaBlob.Purpose storedPurpose;

        GeneratedPurpose(MediaBlob.Purpose storedPurpose) {
            this.storedPurpose = storedPurpose;
        }

        public MediaBlob.Purpose storedPurpose() {
            return storedPurpose;
        }
    }
}
