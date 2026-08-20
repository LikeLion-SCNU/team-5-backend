package org.example.naeilbank.global.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {
    private final ObjectMapper objectMapper;

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode) throws IOException {
        String correlationId = correlationId(request);
        response.setStatus(errorCode.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationId.HEADER_NAME, correlationId);
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
                errorCode.name(),
                errorCode.getMessage(),
                correlationId
        ));
    }

    private String correlationId(HttpServletRequest request) {
        return CorrelationId.resolve(request);
    }
}
