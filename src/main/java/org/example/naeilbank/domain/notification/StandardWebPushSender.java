package org.example.naeilbank.domain.notification;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.global.config.properties.VapidProperties;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class StandardWebPushSender implements WebPushSender {
    private final VapidProperties vapidProperties;
    private final Clock clock;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public SendResult send(WebPushMessage message) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(message.endpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("TTL", "3600")
                    .header("Urgency", "normal")
                    .header("Authorization", "vapid t=" + vapidToken(message.audience()) + ", k=" + vapidProperties.publicKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 201 || status == 202) {
                return SendResult.accepted;
            }
            if (status == 404 || status == 410) {
                return SendResult.expired;
            }
            if (status == 429 || status >= 500) {
                return SendResult.transient_failure;
            }
            return SendResult.permanent_failure;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.transient_failure;
        } catch (Exception e) {
            return SendResult.transient_failure;
        }
    }

    private String vapidToken(String audience) throws Exception {
        Instant now = Instant.now(clock);
        return Jwts.builder()
                .audience().add(audience).and()
                .issuer(vapidProperties.subject())
                .expiration(Date.from(now.plus(Duration.ofHours(12))))
                .issuedAt(Date.from(now))
                .signWith(privateKey(), Jwts.SIG.ES256)
                .compact();
    }

    private PrivateKey privateKey() throws Exception {
        byte[] raw = Base64.getUrlDecoder().decode(padBase64Url(vapidProperties.privateKey()));
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecParameters = parameters.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), ecParameters));
    }

    private String padBase64Url(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }
}
