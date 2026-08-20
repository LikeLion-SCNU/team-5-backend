package org.example.naeilbank.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FixedClockTestConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FixedClockTestConfiguration.class);

    @Test
    void providesFixedUtcClock() {
        contextRunner.run(context -> {
            Clock clock = context.getBean(Clock.class);

            assertThat(Instant.now(clock)).isEqualTo(FixedClockTestConfiguration.FIXED_INSTANT);
            assertThat(clock.getZone().getId()).isEqualTo("Z");
        });
    }

    @Test
    void awaitsAsyncCompletionAgainstFixedClock() {
        contextRunner.run(context -> {
            Clock clock = context.getBean(Clock.class);
            AtomicReference<Instant> observedInstant = new AtomicReference<>();

            CompletableFuture.runAsync(
                    () -> observedInstant.set(Instant.now(clock)),
                    CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS)
            );

            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(observedInstant).hasValue(FixedClockTestConfiguration.FIXED_INSTANT)
            );
        });
    }
}
