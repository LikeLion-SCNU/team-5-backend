package org.example.naeilbank.domain.media;

import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;

public enum MediaUploadPurpose {
    MEAL_INPUT(MediaBlob.Purpose.meal_input, Consent.Purpose.MEAL_AI),
    FACE_INPUT(MediaBlob.Purpose.face_input, Consent.Purpose.FACE_AI);

    private final MediaBlob.Purpose storedPurpose;
    private final Consent.Purpose requiredConsent;

    MediaUploadPurpose(MediaBlob.Purpose storedPurpose, Consent.Purpose requiredConsent) {
        this.storedPurpose = storedPurpose;
        this.requiredConsent = requiredConsent;
    }

    public MediaBlob.Purpose storedPurpose() {
        return storedPurpose;
    }

    public Consent.Purpose requiredConsent() {
        return requiredConsent;
    }

    public static MediaUploadPurpose parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AuthException(ErrorCode.INVALID_MEDIA_PURPOSE);
        }
    }
}
