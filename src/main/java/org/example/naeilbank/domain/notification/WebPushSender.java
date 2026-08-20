package org.example.naeilbank.domain.notification;

public interface WebPushSender {
    SendResult send(WebPushMessage message);

    record WebPushMessage(String endpoint, String userPublicKey, String userAuth, String audience) {
    }

    enum SendResult {
        accepted,
        expired,
        transient_failure,
        permanent_failure
    }
}
