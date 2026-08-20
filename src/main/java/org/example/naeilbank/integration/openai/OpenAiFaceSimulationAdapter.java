package org.example.naeilbank.integration.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.face.FaceGenerationException;
import org.example.naeilbank.domain.face.FaceSimulationImageGenerator;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.global.config.properties.OpenAiProperties;
import org.example.naeilbank.global.config.properties.UploadProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Component
public class OpenAiFaceSimulationAdapter implements FaceSimulationImageGenerator {
    static final String PROMPT_VERSION = "face-simulation-v1";
    private static final String DEFAULT_OUTPUT_CONTENT_TYPE = "image/png";

    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final long maxGeneratedBytes;
    private final long maxResponseBytes;

    public OpenAiFaceSimulationAdapter(
            OpenAiProperties properties,
            UploadProperties uploadProperties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.maxGeneratedBytes = uploadProperties.maxGeneratedSize().toBytes();
        long encodedImageBytes = Math.multiplyExact((maxGeneratedBytes + 2L) / 3L, 4L);
        this.maxResponseBytes = Math.addExact(Math.multiplyExact(encodedImageBytes, 2L), 64L * 1024L);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public FaceGenerationResult generate(
            InputImage sourceImage,
            String trendDescription,
            FaceSimulationOutput.Label requestedLabel
    ) {
        String prompt = switch (requestedLabel) {
            case current -> currentPrompt();
            case improved -> improvedPrompt();
        };
        GeneratedImage image = requestImage(
                sourceImage,
                requestedLabel,
                prompt
        );
        return new FaceGenerationResult(
                properties.imageModel(),
                PROMPT_VERSION,
                List.of(image)
        );
    }

    private GeneratedImage requestImage(
            InputImage sourceImage,
            FaceSimulationOutput.Label label,
            String prompt
    ) {
        OpenAiImageResponse response;
        try {
            response = restClient.post()
                    .uri("/images/edits")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body(sourceImage, prompt))
                    .exchange((request, clientResponse) -> readResponse(clientResponse));
        } catch (FaceGenerationException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new FaceGenerationException(FaceGenerationException.Reason.timeout, "OpenAI face generation timed out");
        } catch (RestClientException e) {
            if (hasCause(e, SocketTimeoutException.class)) {
                throw new FaceGenerationException(FaceGenerationException.Reason.timeout, "OpenAI face generation timed out");
            }
            throw new FaceGenerationException(FaceGenerationException.Reason.malformed_response, "OpenAI returned an invalid response");
        }
        return new GeneratedImage(label, DEFAULT_OUTPUT_CONTENT_TYPE, decodeOne(response));
    }

    private OpenAiImageResponse readResponse(org.springframework.http.client.ClientHttpResponse response)
            throws IOException {
        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw nonSuccess(status, response);
        }
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            throw malformedResponse("OpenAI returned an unsupported response type");
        }
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maxResponseBytes) {
            throw malformedResponse("OpenAI response exceeded the bounded size");
        }
        try {
            return objectMapper.readValue(readBounded(response.getBody()), OpenAiImageResponse.class);
        } catch (JsonProcessingException e) {
            throw malformedResponse("OpenAI returned malformed JSON");
        }
    }

    private FaceGenerationException nonSuccess(
            int status,
            org.springframework.http.client.ClientHttpResponse response
    ) throws IOException {
        if (status == 429) {
            return failure(FaceGenerationException.Reason.rate_limited, "OpenAI rate limited face generation");
        }
        if (status == 401 || status == 403) {
            return failure(FaceGenerationException.Reason.authentication_failed, "OpenAI authentication failed");
        }
        if (status == 400 || status == 422) {
            String errorBody = new String(readBounded(response.getBody()), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (errorBody.contains("content_policy")
                    || errorBody.contains("safety")
                    || errorBody.contains("refusal")) {
                return failure(FaceGenerationException.Reason.safety_refusal, "OpenAI refused unsafe image content");
            }
            return failure(FaceGenerationException.Reason.invalid_request, "OpenAI rejected the image request");
        }
        if (status >= 500) {
            return failure(FaceGenerationException.Reason.upstream_failure, "OpenAI face generation failed");
        }
        return failure(FaceGenerationException.Reason.invalid_request, "OpenAI rejected the image request");
    }

    private FaceGenerationException failure(FaceGenerationException.Reason reason, String message) {
        return new FaceGenerationException(reason, message);
    }

    private byte[] readBounded(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw malformedResponse("OpenAI response exceeded the bounded size");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private MultiValueMap<String, Object> body(InputImage sourceImage, String prompt) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("model", properties.imageModel());
        body.add("prompt", prompt);
        body.add("n", "1");

        HttpHeaders imageHeaders = new HttpHeaders();
        imageHeaders.setContentType(MediaType.parseMediaType(sourceImage.contentType()));
        imageHeaders.setContentDispositionFormData("image", "face-input");
        body.add("image", new HttpEntity<>(sourceImage.content(), imageHeaders));
        return body;
    }

    private String currentPrompt() {
        return """
                Create one non-identifying illustrative image from the user's own adult photo.
                Do not claim identity recognition, biometrics, diagnosis, or accurate future prediction.
                Preserve the current look without adding wellness improvements or deterioration.
                Keep the result realistic, neutral, and suitable for comparison with a separate improved illustration.
                """;
    }

    private String improvedPrompt() {
        return """
                Create one non-identifying illustrative image from the user's own adult photo.
                Do not claim identity recognition, biometrics, diagnosis, or accurate future prediction.
                Use only healthy, natural-looking skin, sleep, hydration, and grooming improvements.
                Keep the result realistic and suitable for comparison with a separate current-look illustration.
                """;
    }

    private byte[] decodeOne(OpenAiImageResponse response) {
        if (response == null || response.data() == null || response.data().size() != 1) {
            throw new FaceGenerationException(
                    FaceGenerationException.Reason.malformed_response,
                    "OpenAI returned an invalid face image count"
            );
        }
        OpenAiImage image = response.data().getFirst();
        if (image == null) {
            throw malformedResponse("OpenAI returned an empty image item");
        }
        return decodeBase64(image.b64Json());
    }

    private byte[] decodeBase64(String value) {
        if (value == null || value.isBlank()) {
            throw new FaceGenerationException(
                    FaceGenerationException.Reason.malformed_response,
                    "OpenAI returned an empty image"
            );
        }
        long maxEncodedBytes = Math.multiplyExact((maxGeneratedBytes + 2L) / 3L, 4L);
        if (value.length() > maxEncodedBytes) {
            throw malformedResponse("OpenAI returned an oversized image");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length > maxGeneratedBytes) {
                throw malformedResponse("OpenAI returned an oversized image");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw malformedResponse("OpenAI returned an invalid image");
        }
    }

    private FaceGenerationException malformedResponse(String message) {
        return new FaceGenerationException(FaceGenerationException.Reason.malformed_response, message);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (causeType.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    record OpenAiImageResponse(List<OpenAiImage> data) {
    }

    record OpenAiImage(@JsonProperty("b64_json") String b64Json) {
    }
}
