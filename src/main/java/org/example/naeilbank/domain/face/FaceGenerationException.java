package org.example.naeilbank.domain.face;

public class FaceGenerationException extends RuntimeException {
    private final Reason reason;

    public FaceGenerationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        timeout,
        rate_limited,
        malformed_response,
        upstream_failure
    }
}
