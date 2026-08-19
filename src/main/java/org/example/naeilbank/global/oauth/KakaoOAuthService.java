package org.example.naeilbank.global.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.global.config.properties.KakaoProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
public class KakaoOAuthService {

    private final KakaoProperties kakaoProperties;

    public String getKakaoAccessToken(String code) {
        RestTemplate rt = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", kakaoProperties.clientId());
        params.add("redirect_uri", kakaoProperties.redirectUri());
        params.add("code", code);
        params.add("client_secret", kakaoProperties.clientSecret());

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
            throw new RuntimeException("카카오 Access Token 발급 실패: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new RuntimeException("카카오 Access Token 발급 중 알 수 없는 에러", e);
        }
    }

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
            throw new RuntimeException("카카오 사용자 정보 조회 실패: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new RuntimeException("카카오 사용자 정보 조회 중 알 수 없는 에러", e);
        }
    }
}
