package org.example.naeilbank.integration.openai;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.example.naeilbank.domain.face.FaceGenerationException;
import org.example.naeilbank.domain.face.FaceSimulationImageGenerator;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.global.config.properties.OpenAiProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiFaceSimulationAdapterTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4XmNgYPgPAAEDAQCjFp8ZAAAAAElFTkSuQmCC"
    );

    @Test
    void successReturnsExactlyCurrentAndImprovedLabels() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("""
                    {"data":[{"b64_json":"%s"},{"b64_json":"%s"}]}
                    """.formatted(b64(PNG), b64(PNG))));

            var result = adapter(server).generate(input(), "skin care");

            assertThat(result.modelVersion()).isEqualTo("face-model");
            assertThat(result.promptVersion()).isEqualTo(OpenAiFaceSimulationAdapter.PROMPT_VERSION);
            assertThat(result.images()).extracting(FaceSimulationImageGenerator.GeneratedImage::label)
                    .containsExactly(FaceSimulationOutput.Label.current, FaceSimulationOutput.Label.improved);
            assertThat(result.images()).allSatisfy(image -> {
                assertThat(image.contentType()).isEqualTo("image/png");
                assertThat(image.content()).isEqualTo(PNG);
            });
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

            assertThatThrownBy(() -> adapter(server, Duration.ofMillis(50)).generate(input(), null))
                    .isInstanceOf(FaceGenerationException.class)
                    .extracting(exception -> ((FaceGenerationException) exception).reason())
                    .isEqualTo(FaceGenerationException.Reason.timeout);
        }
    }

    @Test
    void malformedJsonIsTyped() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("{\"data\":[{\"b64_json\":\"not-base64\"},{\"b64_json\":\"also-bad\"}]}"));

            assertReason(server, FaceGenerationException.Reason.malformed_response);
        }
    }

    @Test
    void oneReturnedImageIsMalformed() throws Exception {
        try (MockWebServer server = startedServer()) {
            server.enqueue(jsonResponse("""
                    {"data":[{"b64_json":"%s"}]}
                    """.formatted(b64(PNG))));

            assertReason(server, FaceGenerationException.Reason.malformed_response);
        }
    }

    private void assertReason(MockWebServer server, FaceGenerationException.Reason expected) {
        assertThatThrownBy(() -> adapter(server).generate(input(), null))
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
                "face-model",
                timeout,
                URI.create(server.url("/v1").toString())
        ));
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
