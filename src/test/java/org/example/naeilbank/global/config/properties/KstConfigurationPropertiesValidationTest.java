package org.example.naeilbank.global.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayDeque;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;

class KstConfigurationPropertiesValidationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(KstPropertiesConfiguration.class);

    @Test
    void rejectsNonKstTimezoneConfiguration() {
        contextRunner
                .withPropertyValues("app.timezone.default-zone=UTC")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("default-zone must be Asia/Seoul");
                });
    }

    @Test
    void commonConfigurationDefaultsMorningStatementToEightKst() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SchedulingProperties.class).morningStatementCron())
                    .isEqualTo("0 0 8 * * *");
            assertThat(context.getBean(TimezoneProperties.class).defaultZone())
                    .isEqualTo("Asia/Seoul");
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
            if (current.getCause() != null && current.getCause() != current) {
                queue.add(current.getCause());
            }
        }
        return messages.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SchedulingProperties.class, TimezoneProperties.class})
    static class KstPropertiesConfiguration {
    }
}
