package org.example.naeilbank.domain.meal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.meal.MealAnalysisContract.AnalyzedMeal;
import org.example.naeilbank.global.config.properties.OpenAiProperties;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiMealAnalysisClient implements MealAnalysisClient {
    private static final String PROMPT = """
            Analyze this meal image for a health ledger draft. Return only JSON matching the schema.
            Use FOOD with PER_SERVING for food and ALCOHOL with PER_DRINK for alcoholic drinks.
            Do not include unsupported categories.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;

    public OpenAiMealAnalysisClient(RestClient.Builder builder,
                                    ObjectMapper objectMapper,
                                    OpenAiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = builder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public AnalyzedMeal analyze(String contentType, byte[] imageBytes) {
        String image = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> request = Map.of(
                "model", properties.mealModel(),
                "input", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", PROMPT),
                                Map.of("type", "input_image", "image_url", image)
                        )
                )),
                "text", Map.of("format", Map.of(
                        "type", "json_schema",
                        "name", "meal_analysis",
                        "strict", true,
                        "schema", schema()
                ))
        );
        try {
            JsonNode response = restClient.post()
                    .uri(properties.responsesUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return MealAnalysisContract.parse(objectMapper, extractText(response));
        } catch (AuthException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AuthException(ErrorCode.MEAL_ANALYSIS_FAILED);
        }
    }

    private String extractText(JsonNode response) {
        if (response == null) {
            throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
        }
        JsonNode outputText = response.get("output_text");
        if (outputText != null && outputText.isTextual()) {
            return outputText.asText();
        }
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
        }
        for (JsonNode message : output) {
            JsonNode content = message.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                JsonNode text = part.get("text");
                if (text != null && text.isTextual()) {
                    return text.asText();
                }
            }
        }
        throw new AuthException(ErrorCode.INVALID_MEAL_ANALYSIS);
    }

    private Map<String, Object> schema() {
        Map<String, Object> item = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("food_name", "portion", "category", "unit", "value"),
                "properties", Map.of(
                        "food_name", Map.of("type", "string", "minLength", 1),
                        "portion", Map.of("type", "string"),
                        "category", Map.of("type", "string", "enum", List.of("FOOD", "ALCOHOL")),
                        "unit", Map.of("type", "string", "enum", List.of("PER_SERVING", "PER_DRINK")),
                        "value", Map.of("type", "number", "exclusiveMinimum", 0, "maximum", 1000)
                )
        );
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("items"),
                "properties", Map.of("items", Map.of("type", "array", "maxItems", 30, "items", item))
        );
    }
}
