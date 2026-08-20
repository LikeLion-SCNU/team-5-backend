package org.example.naeilbank.global.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void acceptsActualProductionProfileWithInjectedFixturesAndPostgresPasswordFallback() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues(validDeploymentEnvironment())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AppDataSourceProperties.class).password())
                            .isEqualTo("prod-db-password-fixture");
                    assertThat(context.getBean(JwtProperties.class).accessTokenTtl())
                            .isEqualTo(Duration.ofMinutes(30));
                    assertThat(context.getBean(JwtProperties.class).refreshTokenTtl())
                            .isEqualTo(Duration.ofDays(14));
                    assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                            .containsExactly("https://timebank.hbinserver.cloud");
                    assertThat(context.getBean(CorsProperties.class).allowedHeaders())
                            .contains("If-None-Match");
                    assertThat(context.getBean(CorsProperties.class).exposedHeaders())
                            .contains("ETag");
                    assertThat(context.getBean(UploadProperties.class).maxInputSize().toBytes())
                            .isEqualTo(10L * 1024 * 1024);
                    assertThat(context.getEnvironment().getProperty(
                            "spring.servlet.multipart.max-file-size"
                    )).isEqualTo("10MB");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.servlet.multipart.max-request-size"
                    )).isEqualTo("11MB");
                });
    }

    @Test
    void rejectsUploadLimitsAboveDatabaseAndApiBounds() {
        contextRunner
                .withPropertyValues("UPLOAD_MAX_INPUT_SIZE=11MB")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("max-input-size must not exceed 10 MiB");
                });
        contextRunner
                .withPropertyValues("UPLOAD_MAX_GENERATED_SIZE=21MB")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("max-generated-size must not exceed 20 MiB");
                });
        contextRunner
                .withPropertyValues("UPLOAD_MAX_REQUEST_SIZE=12MB")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("max-request-size must accommodate input and not exceed 11 MiB");
                });
        contextRunner
                .withPropertyValues("UPLOAD_MAX_REQUEST_SIZE=10MB")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("max-request-size must accommodate input and not exceed 11 MiB");
                });
    }

    @Test
    void rejectsEachMissingProductionDeploymentValueFromActualProfile() {
        Map<String, String> requiredValues = Map.ofEntries(
                Map.entry("POSTGRES_PASSWORD", "password"),
                Map.entry("JWT_SECRET", "secret"),
                Map.entry("KAKAO_CLIENT_ID", "clientId"),
                Map.entry("KAKAO_REDIRECT_URI", "redirectUri"),
                Map.entry("KAKAO_CLIENT_SECRET", "clientSecret"),
                Map.entry("OPENAI_API_KEY", "apiKey"),
                Map.entry("VAPID_PUBLIC_KEY", "publicKey"),
                Map.entry("VAPID_PRIVATE_KEY", "privateKey"),
                Map.entry("VAPID_SUBJECT", "subject"),
                Map.entry("CORS_ORIGINS", "allowedOrigins")
        );

        requiredValues.forEach(this::assertBlankDeploymentValueFails);
    }

    @Test
    void rejectsBlankSpringDatasourcePasswordEvenWhenPostgresPasswordExists() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues(validDeploymentEnvironment())
                .withPropertyValues("SPRING_DATASOURCE_PASSWORD=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure())).contains("password");
                });
    }

    @Test
    void rejectsWildcardCredentialedCors() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues(validDeploymentEnvironment())
                .withPropertyValues("CORS_ORIGINS=*")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("credentialed CORS cannot use wildcard origins");
                });
    }

    @Test
    void rejectsBlankCorsOrigins() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues(validDeploymentEnvironment())
                .withPropertyValues("CORS_ORIGINS=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("allowedOrigins")
                            .doesNotContain("prod-vapid-private-key-fixture")
                            .doesNotContain("prod-openai-api-key-fixture");
                });
    }

    @Test
    void startupFailureDoesNotEchoSecretValue() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues(validDeploymentEnvironment())
                .withPropertyValues("JWT_SECRET=short-secret-value")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("size must be between 32")
                            .doesNotContain("short-secret-value")
                            .doesNotContain("prod-openai-api-key-fixture")
                            .doesNotContain("prod-vapid-private-key-fixture");
                });
    }

    private void assertBlankDeploymentValueFails(String environmentName, String expectedFieldName) {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues(validDeploymentEnvironment())
                .withPropertyValues(environmentName + "=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains(expectedFieldName)
                            .doesNotContain("prod-jwt-secret-fixture-32-bytes-minimum")
                            .doesNotContain("prod-openai-api-key-fixture")
                            .doesNotContain("prod-vapid-private-key-fixture");
                });
    }

    private String failureMessages(Throwable failure) {
        StringJoiner messages = new StringJoiner("\n");
        ArrayDeque<Throwable> queue = new ArrayDeque<>();
        if (failure != null) {
            queue.add(failure);
        }
        while (!queue.isEmpty()) {
            Throwable current = queue.removeFirst();
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                queue.add(cause);
            }
            for (Throwable suppressed : current.getSuppressed()) {
                queue.add(suppressed);
            }
        }
        return messages.toString();
    }

    private String[] validDeploymentEnvironment() {
        return new String[]{
                "SPRING_DATASOURCE_URL=jdbc:postgresql://naeil-db:5432/naeil_bank",
                "SPRING_DATASOURCE_USERNAME=naeil",
                "POSTGRES_PASSWORD=prod-db-password-fixture",
                "JWT_SECRET=prod-jwt-secret-fixture-32-bytes-minimum",
                "KAKAO_CLIENT_ID=prod-kakao-client-id",
                "KAKAO_REDIRECT_URI=https://timebank.hbinserver.cloud/api/oauth/kakao/callback",
                "KAKAO_CLIENT_SECRET=prod-kakao-client-secret",
                "OPENAI_API_KEY=prod-openai-api-key-fixture",
                "VAPID_PUBLIC_KEY=prod-vapid-public-key-fixture",
                "VAPID_PRIVATE_KEY=prod-vapid-private-key-fixture",
                "VAPID_SUBJECT=mailto:admin@example.com",
                "CORS_ORIGINS=https://timebank.hbinserver.cloud"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            AppDataSourceProperties.class,
            CorsProperties.class,
            JwtProperties.class,
            KakaoProperties.class,
            OpenAiProperties.class,
            SchedulingProperties.class,
            TimezoneProperties.class,
            UploadProperties.class,
            VapidProperties.class
    })
    static class PropertiesConfiguration {
    }
}
