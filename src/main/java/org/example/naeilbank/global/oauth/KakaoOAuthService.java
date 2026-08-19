package org.example.naeilbank.global.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Service
public class KakaoOAuthService {

    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    @Value("${kakao.client-secret}")
    private String clientSecret; // 🔥 Client Secret 주입

    // 1. 인가 코드로 카카오 Access Token 발급
    public String getKakaoAccessToken(String code) {
        RestTemplate rt = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", clientId);
        params.add("redirect_uri", redirectUri);
        params.add("code", code);
        params.add("client_secret", clientSecret); // 🔥 카카오로 client_secret 추가 전송!

        HttpEntity<MultiValueMap<String, String>> kakaoTokenRequest = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<String> response = rt.exchange(
                    "https://kauth.kakao.com/oauth/token",
                    HttpMethod.POST,
                    kakaoTokenRequest,
                    String.class
            );

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(response.getBody());
            return jsonNode.get("access_token").asText();

        } catch (HttpStatusCodeException e) {
            System.err.println("카카오 토큰 에러 상태코드: " + e.getStatusCode());
            System.err.println("카카오 토큰 에러 본문: " + e.getResponseBodyAsString());
            throw new RuntimeException("카카오 Access Token 발급 실패: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException("카카오 Access Token 발급 중 알 수 없는 에러", e);
        }
    }

    // 2. 카카오 Access Token으로 사용자 프로필 조회
    public KakaoUserInfo getKakaoUserInfo(String accessToken) {
        RestTemplate rt = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        HttpEntity<MultiValueMap<String, String>> kakaoProfileRequest = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = rt.exchange(
                    "https://kapi.kakao.com/v2/user/me",
                    HttpMethod.POST,
                    kakaoProfileRequest,
                    String.class
            );

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(response.getBody());

            Long id = jsonNode.get("id").asLong();

            String nickname = "카카오유저";
            JsonNode properties = jsonNode.get("properties");
            if (properties != null && properties.has("nickname")) {
                nickname = properties.get("nickname").asText();
            }

            String email;
            JsonNode kakaoAccount = jsonNode.get("kakao_account");
            if (kakaoAccount != null && kakaoAccount.has("email")) {
                email = kakaoAccount.get("email").asText();
            } else {
                email = "kakao_" + id + "@test.com";
            }

            return new KakaoUserInfo(id, email, nickname);

        } catch (HttpStatusCodeException e) {
            System.err.println("카카오 유저정보 에러 본문: " + e.getResponseBodyAsString());
            throw new RuntimeException("카카오 사용자 정보 조회 실패: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException("카카오 사용자 정보 조회 중 알 수 없는 에러", e);
        }
    }
}