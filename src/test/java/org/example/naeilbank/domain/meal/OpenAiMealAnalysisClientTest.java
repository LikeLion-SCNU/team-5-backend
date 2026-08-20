package org.example.naeilbank.domain.meal;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.global.config.properties.OpenAiProperties;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiMealAnalysisClientTest {
    private MockWebServer server;
    private OpenAiMealAnalysisClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        OpenAiProperties properties = new OpenAiProperties(
                "test-openai-key",
                "gpt-5-mini",
                "gpt-5-mini",
                Duration.ofSeconds(5),
                URI.create(server.url("/v1/responses").toString())
        );
        client = new OpenAiMealAnalysisClient(RestClient.builder(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsStrictJsonSchemaRequestAndParsesTypedMealJson() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"output":[{"content":[{"type":"output_text","text":"{\\"items\\":[{\\"food_name\\":\\"spinach\\",\\"portion\\":\\"1 serving\\",\\"category\\":\\"FOOD\\",\\"unit\\":\\"PER_SERVING\\",\\"value\\":1,\\"eligibility\\":\\"FRUIT_OR_VEGETABLE\\"}]}"}]}]}
                        """));

        MealAnalysisContract.AnalyzedMeal meal = client.analyze("image/png", new byte[]{1, 2, 3});

        assertThat(meal.items()).hasSize(1);
        assertThat(meal.items().getFirst().category()).isEqualTo(HabitCategory.FOOD);
        assertThat(meal.items().getFirst().unit()).isEqualTo(ConversionUnit.PER_SERVING);
        assertThat(meal.items().getFirst().eligibility()).isEqualTo(MealEligibility.FRUIT_OR_VEGETABLE);
        String requestBody = server.takeRequest().getBody().readUtf8();
        assertThat(requestBody)
                .contains("\"strict\":true")
                .contains("\"json_schema\"")
                .contains("FRUIT_OR_VEGETABLE")
                .contains("\"image_url\":\"data:image/png;base64,AQID\"");
    }

    @Test
    void rejectsContractDriftFromOpenAiResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"output_text":"{\\"items\\":[{\\"food_name\\":\\"rice\\",\\"portion\\":\\"1 bowl\\",\\"category\\":\\"FOOD\\",\\"unit\\":\\"PER_DRINK\\",\\"value\\":1,\\"eligibility\\":\\"NEUTRAL\\",\\"extra\\":true}]}"}
                        """));

        assertThatThrownBy(() -> client.analyze("image/png", new byte[]{1}))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_MEAL_ANALYSIS));
    }

    @Test
    void mapsReadTimeoutToTypedAnalysisFailure() {
        OpenAiProperties shortTimeout = new OpenAiProperties(
                "test-openai-key",
                "gpt-5-mini",
                "gpt-5-mini",
                Duration.ofMillis(50),
                URI.create(server.url("/v1/responses").toString())
        );
        OpenAiMealAnalysisClient timeoutClient = new OpenAiMealAnalysisClient(
                RestClient.builder(), new ObjectMapper(), shortTimeout);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"output_text\":\"{\\\"items\\\":[]}\"}")
                .setBodyDelay(1, TimeUnit.SECONDS));

        assertThatThrownBy(() -> timeoutClient.analyze("image/png", new byte[]{1}))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.MEAL_ANALYSIS_FAILED));
    }
}
