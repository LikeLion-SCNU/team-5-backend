package org.example.naeilbank.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException e, HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("AuthException: {}", errorCode.name());

        return response(errorCode, errorCode.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getField)
                .map(field -> "요청 값이 올바르지 않습니다: " + field)
                .orElse(ErrorCode.VALIDATION_FAILED.getMessage());
        return response(ErrorCode.VALIDATION_FAILED, detail, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return response(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getMessage(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return response(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return response(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException e,
            HttpServletRequest request
    ) {
        return response(ErrorCode.MEDIA_TOO_LARGE, ErrorCode.MEDIA_TOO_LARGE.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception", e);
        String correlationId = correlationId(request);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(CorrelationId.HEADER_NAME, correlationId)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.", correlationId));
    }

    private ResponseEntity<ErrorResponse> response(ErrorCode errorCode, String message, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .header(CorrelationId.HEADER_NAME, correlationId)
                .body(new ErrorResponse(errorCode.name(), message, correlationId));
    }

    private String correlationId(HttpServletRequest request) {
        return CorrelationId.resolve(request);
    }
}
