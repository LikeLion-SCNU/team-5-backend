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

import java.util.Locale;

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
                    kakaoProperties.tokenUri(),
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
                    kakaoProperties.userInfoUri(),
                    HttpMethod.POST,
                    kakaoProfileRequest,
                    String.class
            );

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(response.getBody());

            JsonNode idNode = jsonNode.get("id");
            if (idNode == null || idNode.isNull() || !idNode.isIntegralNumber() || !idNode.canConvertToLong()
                    || idNode.asLong() <= 0) {
                throw new IllegalArgumentException("Kakao id is required");
            }
            Long id = idNode.asLong();

            String nickname = "카카오유저";
            JsonNode properties = jsonNode.get("properties");
            if (properties != null && properties.has("nickname")) {
                nickname = properties.get("nickname").asText();
            }

            String email = resolveEmail(jsonNode.get("kakao_account"), id);

            return new KakaoUserInfo(id, email, nickname);

        } catch (HttpStatusCodeException e) {
            throw new RuntimeException("카카오 사용자 정보 조회 실패: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new RuntimeException("카카오 사용자 정보 조회 중 알 수 없는 에러", e);
        }
    }

    private String resolveEmail(JsonNode kakaoAccount, Long id) {
        if (kakaoAccount != null) {
            JsonNode emailNode = kakaoAccount.get("email");
            String email = emailNode == null || emailNode.isNull() ? "" : emailNode.asText().trim();
            if (!email.isBlank()
                    && explicitTrue(kakaoAccount, "is_email_valid")
                    && explicitTrue(kakaoAccount, "is_email_verified")) {
                return email.toLowerCase(Locale.ROOT);
            }
        }
        return "kakao_" + id + "@users.invalid";
    }

    private boolean explicitTrue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value != null && value.isBoolean() && value.asBoolean();
    }
}
