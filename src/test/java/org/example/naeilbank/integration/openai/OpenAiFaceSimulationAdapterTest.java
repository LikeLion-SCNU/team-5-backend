package org.example.naeilbank.integration.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.example.naeilbank.domain.face.FaceGenerationException;
import org.example.naeilbank.domain.face.FaceSimulationImageGenerator;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.global.config.properties.OpenAiProperties;
import org.example.naeilbank.global.config.properties.UploadProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiFaceSimulationAdapterTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4XmNgYPgPAAEDAQCjFp8ZAAAAAElFTkSuQmCC"
    );

    @Test
    void successReturnsExactlyCurrentAndImprovedLabels() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("""
                    {"data":[{"b64_json":"%s"}]}
                    """.formatted(b64(PNG))));
            server.enqueue(jsonResponse("""
                    {"data":[{"b64_json":"%s"}]}
                    """.formatted(b64(PNG))));

            var current = adapter(server).generate(input(), "skin care", FaceSimulationOutput.Label.current);
            var improved = adapter(server).generate(input(), "skin care", FaceSimulationOutput.Label.improved);

            assertThat(current.modelVersion()).isEqualTo("gpt-image-2");
            assertThat(current.promptVersion()).isEqualTo(OpenAiFaceSimulationAdapter.PROMPT_VERSION);
            assertThat(current.images()).extracting(FaceSimulationImageGenerator.GeneratedImage::label)
                    .containsExactly(FaceSimulationOutput.Label.current);
            assertThat(improved.images()).extracting(FaceSimulationImageGenerator.GeneratedImage::label)
                    .containsExactly(FaceSimulationOutput.Label.improved);
            assertThat(java.util.stream.Stream.concat(current.images().stream(), improved.images().stream())).allSatisfy(image -> {
                assertThat(image.contentType()).isEqualTo("image/png");
                assertThat(image.content()).isEqualTo(PNG);
            });
            RecordedRequest currentRequest = server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest improvedRequest = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(currentRequest).isNotNull();
            assertThat(improvedRequest).isNotNull();
            String currentBody = currentRequest.getBody().readUtf8();
            String improvedBody = improvedRequest.getBody().readUtf8();
            assertThat(currentBody)
                    .contains("name=\"model\"", "gpt-image-2", "name=\"image\"")
                    .contains("name=\"n\"", "1")
                    .doesNotContain("response_format", "skin care");
            assertThat(improvedBody)
                    .contains("name=\"model\"", "gpt-image-2", "name=\"image\"")
                    .contains("name=\"n\"", "1")
                    .doesNotContain("response_format", "skin care");
            assertThat(currentBody).isNotEqualTo(improvedBody);
        }
    }

    @Test
    void rateLimitIsTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(new MockResponse().setResponseCode(429));

            assertReason(server, FaceGenerationException.Reason.rate_limited);
        }
    }

    @Test
    void timeoutIsTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("{\"data\":[]}").setBodyDelay(300, TimeUnit.MILLISECONDS));

            assertThatThrownBy(() -> adapter(server, Duration.ofMillis(50)).generate(
                    input(), null, FaceSimulationOutput.Label.current))
                    .isInstanceOf(FaceGenerationException.class)
                    .extracting(exception -> ((FaceGenerationException) exception).reason())
                    .isEqualTo(FaceGenerationException.Reason.timeout);
        }
    }

    @Test
    void invalidBase64IsTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("{\"data\":[{\"b64_json\":\"not-base64\"}]}"));

            assertReason(server, FaceGenerationException.Reason.malformed_response);
        }
    }

    @Test
    void unsupportedResponseTypeIsTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/plain")
                    .setBody("not-json"));

            assertReason(server, FaceGenerationException.Reason.malformed_response);
        }
    }

    @Test
    void secondRequestFailureReturnsNoPartialResult() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("""
                    {"data":[{"b64_json":"%s"}]}
                    """.formatted(b64(PNG))));
            server.enqueue(new MockResponse().setResponseCode(500));

            adapter(server).generate(input(), null, FaceSimulationOutput.Label.current);
            assertThatThrownBy(() -> adapter(server).generate(
                    input(), null, FaceSimulationOutput.Label.improved))
                    .isInstanceOf(FaceGenerationException.class)
                    .extracting(exception -> ((FaceGenerationException) exception).reason())
                    .isEqualTo(FaceGenerationException.Reason.upstream_failure);
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void multipleReturnedImagesAreMalformed() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("""
                    {"data":[{"b64_json":"%s"},{"b64_json":"%s"}]}
                    """.formatted(b64(PNG), b64(PNG))));

            assertReason(server, FaceGenerationException.Reason.malformed_response);
        }
    }

    @Test
    void authenticationFailureIsTerminalAndTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));

            assertReason(server, FaceGenerationException.Reason.authentication_failed);
        }
    }

    @Test
    void safetyRefusalIsTerminalAndTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("""
                    {"error":{"code":"content_policy_violation","message":"safety refusal"}}
                    """).setResponseCode(400));

            assertReason(server, FaceGenerationException.Reason.safety_refusal);
        }
    }

    @Test
    void oversizedResponseIsRejectedBeforeJsonBinding() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("{}").setHeader("Content-Length", 4_000_000));

            assertReason(server, FaceGenerationException.Reason.malformed_response);
        }
    }

    @Test
    void upstreamServerFailureIsTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));

            assertReason(server, FaceGenerationException.Reason.upstream_failure);
        }
    }

    @Test
    void otherClientErrorsAreTerminalAndTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(new MockResponse().setResponseCode(409));

            assertReason(server, FaceGenerationException.Reason.invalid_request);
        }
    }

    @Test
    void providerSecretsAreNotLoggedOrExposed(CapturedOutput output) throws Exception {
        try (MockWebServer server = startedServer()) {
            String sensitive = "provider-sensitive-fixture";
            server.enqueue(new MockResponse()
                    .setResponseCode(401)
                    .setBody("{\"error\":\"" + sensitive + "\"}"));

            assertThatThrownBy(() -> adapter(server).generate(
                    input(), null, FaceSimulationOutput.Label.current))
                    .isInstanceOf(FaceGenerationException.class)
                    .hasMessageNotContaining(sensitive)
                    .hasMessageNotContaining("test-api-key");
            assertThat(output).doesNotContain(sensitive, "test-api-key");
        }
    }

    private void assertReason(MockWebServer server, FaceGenerationException.Reason expected) {
        assertThatThrownBy(() -> adapter(server).generate(
                input(), null, FaceSimulationOutput.Label.current))
                .isInstanceOf(FaceGenerationException.class)
                .extracting(exception -> ((FaceGenerationException) exception).reason())
                .isEqualTo(expected);
    }

    private OpenAiFaceSimulationAdapter adapter(MockWebServer server) {
        return adapter(server, Duration.ofSeconds(2));
    }

    private OpenAiFaceSimulationAdapter adapter(MockWebServer server, Duration timeout) {
        return new OpenAiFaceSimulationAdapter(new OpenAiProperties(
                "test-api-key",
                "meal-model",
                "gpt-image-2",
                timeout,
                URI.create(server.url("/v1").toString())
        ), new UploadProperties(
                DataSize.ofMegabytes(1),
                DataSize.ofMegabytes(2),
                DataSize.ofMegabytes(1)
        ), new ObjectMapper());
    }

    private FaceSimulationImageGenerator.InputImage input() {
        return new FaceSimulationImageGenerator.InputImage("image/png", PNG);
    }

    private MockWebServer startedServer() throws java.io.IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        return server;
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private String b64(byte[] content) {
        return Base64.getEncoder().encodeToString(content);
    }
}
