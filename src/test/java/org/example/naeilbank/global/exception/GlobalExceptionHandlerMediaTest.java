package org.example.naeilbank.global.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerMediaTest {
    @Test
    void multipartResolverLimitUsesTypedPayloadTooLargeResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/media/MEAL_INPUT");

        var response = new GlobalExceptionHandler().handleMaxUploadSize(
                new MaxUploadSizeExceededException(10L * 1024 * 1024),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("MEDIA_TOO_LARGE");
        assertThat(response.getBody().message()).doesNotContain("file", "filename");
    }
}
