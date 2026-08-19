package org.example.naeilbank.global.config.properties;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {

    @Test
    void credentialedCorsFailsClosedWhenOriginsAreNull() {
        CorsProperties properties = new CorsProperties(
                true,
                null,
                List.of("GET"),
                List.of("Authorization"),
                List.of("Location")
        );

        assertThat(properties.isCredentialedOriginSafe()).isFalse();
    }
}
