package org.example.naeilbank.controller;

import org.example.naeilbank.domain.auth.dto.AuthDtos.KakaoLoginRequest;
import org.example.naeilbank.domain.auth.dto.AuthDtos.TokenResponse;
import org.example.naeilbank.domain.auth.dto.AuthDtos.UserSummary;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.global.exception.GlobalExceptionHandler;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.example.naeilbank.global.security.AuthRateLimitFilter;
import org.example.naeilbank.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthKakaoHttpContractTest {
    @Autowired
    MockMvc mockMvc;

    @MockBean
    AuthService authService;

    @MockBean
    JwtTokenProvider jwtTokenProvider;

    @MockBean
    AuthRateLimitFilter authRateLimitFilter;

    @Test
    void kakaoPostSuccessUsesCamelCaseTokenResponse() throws Exception {
        when(authService.kakaoLogin(any(KakaoLoginRequest.class))).thenReturn(new TokenResponse(
                "access-token",
                "refresh-token",
                "Bearer",
                1800,
                new UserSummary(UUID.randomUUID(), "kakao_1@users.invalid", "ROLE_USER")
        ));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"valid-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.user.email").value("kakao_1@users.invalid"));
    }

    @Test
    void kakaoPostProviderFailureIsNormalized() throws Exception {
        when(authService.kakaoLogin(any(KakaoLoginRequest.class)))
                .thenThrow(new AuthException(ErrorCode.KAKAO_AUTH_FAILED));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"provider-failure\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_AUTH_FAILED"));
    }

    @Test
    void kakaoGetIsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/auth/kakao"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void kakaoCodeValidationRejectsMissingNullAndBlankCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(authService);
    }
}
