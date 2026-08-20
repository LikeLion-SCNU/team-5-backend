package org.example.naeilbank.global.exception;


import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 액세스 토큰입니다."),
    ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "만료된 액세스 토큰입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "만료된 리프레시 토큰입니다."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "재사용된 리프레시 토큰입니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 자원에 접근할 권한이 없습니다."),
    INVALID_CONSENT_PURPOSE(HttpStatus.BAD_REQUEST, "지원하지 않는 동의 목적입니다."),
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "해당 기능에 필요한 동의가 없습니다."),
    CONSENT_VERSION_CONFLICT(HttpStatus.CONFLICT, "동의 상태가 변경되었습니다. 최신 상태를 다시 확인해주세요."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일한 멱등키가 다른 요청에 사용되었습니다."),
    INVALID_MEDIA_PURPOSE(HttpStatus.BAD_REQUEST, "지원하지 않는 미디어 목적입니다."),
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "유효한 이미지 파일이 아닙니다."),
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "미디어를 찾을 수 없습니다."),
    MEDIA_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "미디어 크기 제한을 초과했습니다."),
    MEDIA_TYPE_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 이미지 형식입니다."),
    MEDIA_TYPE_MISMATCH(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "파일 내용과 선언된 이미지 형식이 다릅니다."),
    MEDIA_IN_USE(HttpStatus.CONFLICT, "사용 중인 미디어는 삭제할 수 없습니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했습니다."),
    INVALID_KAKAO_CODE(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 카카오 인가 코드입니다.");

    private final HttpStatus httpStatus;
    private final String message;
}
