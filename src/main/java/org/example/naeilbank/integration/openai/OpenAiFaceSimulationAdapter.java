package org.example.naeilbank.integration.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.naeilbank.domain.face.FaceGenerationException;
import org.example.naeilbank.domain.face.FaceSimulationImageGenerator;
import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.example.naeilbank.global.config.properties.OpenAiProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class OpenAiFaceSimulationAdapter implements FaceSimulationImageGenerator {
    static final String PROMPT_VERSION = "face-simulation-v1";
    private static final String DEFAULT_OUTPUT_CONTENT_TYPE = "image/png";

    private final OpenAiProperties properties;
    private final RestClient restClient;

    public OpenAiFaceSimulationAdapter(OpenAiProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public FaceGenerationResult generate(InputImage sourceImage, String trendDescription) {
        OpenAiImageResponse response;
        try {
            response = restClient.post()
                    .uri("/images/edits")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body(sourceImage, prompt(trendDescription)))
                    .retrieve()
                    .body(OpenAiImageResponse.class);
        } catch (ResourceAccessException e) {
            throw new FaceGenerationException(FaceGenerationException.Reason.timeout, "OpenAI face generation timed out");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                throw new FaceGenerationException(FaceGenerationException.Reason.rate_limited, "OpenAI rate limited face generation");
            }
            throw new FaceGenerationException(FaceGenerationException.Reason.upstream_failure, "OpenAI face generation failed");
        }
        return new FaceGenerationResult(properties.faceModel(), PROMPT_VERSION, decode(response));
    }

    private MultiValueMap<String, HttpEntity<?>> body(InputImage sourceImage, String prompt) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("model", properties.faceModel());
        builder.part("prompt", prompt);
        builder.part("n", "2");
        builder.part("response_format", "b64_json");
        builder.part("image", new ByteArrayResource(sourceImage.content()) {
            @Override
            public String getFilename() {
                return "face-input";
            }
        }).header(HttpHeaders.CONTENT_TYPE, sourceImage.contentType());
        return builder.build();
    }

    private String prompt(String trendDescription) {
        String trend = trendDescription == null || trendDescription.isBlank()
                ? "healthy, natural-looking skin and grooming improvements"
                : trendDescription.trim();
        return """
                Create exactly two non-identifying illustrative face simulation images from the user's own adult photo.
                Do not claim identity recognition, biometrics, diagnosis, or accurate future prediction.
                Output 1 should preserve the current look. Output 2 should show a realistic improved wellness-oriented look.
                Requested trend: %s
                """.formatted(trend);
    }

    private List<GeneratedImage> decode(OpenAiImageResponse response) {
        if (response == null || response.data() == null || response.data().size() != 2) {
            throw new FaceGenerationException(
                    FaceGenerationException.Reason.malformed_response,
                    "OpenAI returned an invalid face image count"
            );
        }
        List<GeneratedImage> images = new ArrayList<>(2);
        images.add(new GeneratedImage(
                FaceSimulationOutput.Label.current,
                DEFAULT_OUTPUT_CONTENT_TYPE,
                decodeBase64(response.data().get(0).b64Json())
        ));
        images.add(new GeneratedImage(
                FaceSimulationOutput.Label.improved,
                DEFAULT_OUTPUT_CONTENT_TYPE,
                decodeBase64(response.data().get(1).b64Json())
        ));
        return images;
    }

    private byte[] decodeBase64(String value) {
        if (value == null || value.isBlank()) {
            throw new FaceGenerationException(
                    FaceGenerationException.Reason.malformed_response,
                    "OpenAI returned an empty image"
            );
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new FaceGenerationException(
                    FaceGenerationException.Reason.malformed_response,
                    "OpenAI returned an invalid image"
            );
        }
    }

    record OpenAiImageResponse(List<OpenAiImage> data) {
    }

    record OpenAiImage(@JsonProperty("b64_json") String b64Json) {
    }
}
