package org.example.naeilbank.global.oauth;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.example.naeilbank.global.config.properties.KakaoProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoOAuthServiceTest {
    private final MockWebServer kakao = new MockWebServer();

    @AfterEach
    void shutdown() throws IOException {
        kakao.shutdown();
    }

    @Test
    void userInfoUsesLowercaseRealEmailWhenProviderEmailIsUsable() throws IOException {
        kakao.start();
        kakao.enqueue(userInfo("""
                {
                  "id": 1001,
                  "properties": {"nickname": "kakao-user"},
                  "kakao_account": {
                    "email": "Real.User@Example.COM",
                    "is_email_valid": true,
                    "is_email_verified": true
                  }
                }
                """));

        KakaoUserInfo userInfo = service().getKakaoUserInfo("access-token");

        assertThat(userInfo.getId()).isEqualTo(1001L);
        assertThat(userInfo.getEmail()).isEqualTo("real.user@example.com");
        assertThat(userInfo.getNickname()).isEqualTo("kakao-user");
    }

    @Test
    void userInfoFallsBackToDeterministicInvalidDomainWhenEmailIsMissingOrUnusable() throws IOException {
        kakao.start();
        kakao.enqueue(userInfo("""
                {"id": 2001, "properties": {"nickname": "no-account"}}
                """));
        kakao.enqueue(userInfo("""
                {"id": 2002, "kakao_account": {"email": null}}
                """));
        kakao.enqueue(userInfo("""
                {"id": 2003, "kakao_account": {"email": "   "}}
                """));
        kakao.enqueue(userInfo("""
                {"id": 2004, "kakao_account": {"email": "unverified@example.com", "is_email_verified": false}}
                """));
        kakao.enqueue(userInfo("""
                {"id": 2005, "kakao_account": {"email": "missing-flags@example.com"}}
                """));
        kakao.enqueue(userInfo("""
                {"id": 2006, "kakao_account": {"email": "null-flags@example.com", "is_email_valid": null, "is_email_verified": true}}
                """));
        kakao.enqueue(userInfo("""
                {"id": 2007, "kakao_account": {"email": "string-flags@example.com", "is_email_valid": "true", "is_email_verified": true}}
                """));

        assertThat(service().getKakaoUserInfo("access-token").getEmail()).isEqualTo("kakao_2001@users.invalid");
        assertThat(service().getKakaoUserInfo("access-token").getEmail()).isEqualTo("kakao_2002@users.invalid");
        assertThat(service().getKakaoUserInfo("access-token").getEmail()).isEqualTo("kakao_2003@users.invalid");
        assertThat(service().getKakaoUserInfo("access-token").getEmail()).isEqualTo("kakao_2004@users.invalid");
        assertThat(service().getKakaoUserInfo("access-token").getEmail()).isEqualTo("kakao_2005@users.invalid");
        assertThat(service().getKakaoUserInfo("access-token").getEmail()).isEqualTo("kakao_2006@users.invalid");
        assertThat(service().getKakaoUserInfo("access-token").getEmail()).isEqualTo("kakao_2007@users.invalid");
    }

    @Test
    void userInfoFailsClosedWhenKakaoIdIsMissingOrNotPositiveIntegralNumber() throws IOException {
        kakao.start();
        kakao.enqueue(userInfo("""
                {"properties": {"nickname": "missing-id"}}
                """));
        kakao.enqueue(userInfo("""
                {"id": "2008", "properties": {"nickname": "string-id"}}
                """));
        kakao.enqueue(userInfo("""
                {"id": 2009.5, "properties": {"nickname": "fraction-id"}}
                """));
        kakao.enqueue(userInfo("""
                {"id": 0, "properties": {"nickname": "zero-id"}}
                """));
        kakao.enqueue(userInfo("""
                {"id": -1, "properties": {"nickname": "negative-id"}}
                """));

        assertThatThrownBy(() -> service().getKakaoUserInfo("access-token"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service().getKakaoUserInfo("access-token"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service().getKakaoUserInfo("access-token"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service().getKakaoUserInfo("access-token"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service().getKakaoUserInfo("access-token"))
                .isInstanceOf(RuntimeException.class);
    }

    private KakaoOAuthService service() {
        KakaoProperties properties = new KakaoProperties(
                "client-id",
                "http://localhost/oauth/kakao/callback",
                "client-secret",
                kakao.url("/oauth/token").toString(),
                kakao.url("/v2/user/me").toString()
        );
        return new KakaoOAuthService(properties);
    }

    private MockResponse userInfo(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
