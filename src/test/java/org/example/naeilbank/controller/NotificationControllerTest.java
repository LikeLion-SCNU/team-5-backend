package org.example.naeilbank.controller;

import org.example.naeilbank.domain.notification.NotificationDtos.PreferenceRequest;
import org.example.naeilbank.domain.notification.NotificationDtos.PreferenceResponse;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionRequest;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionResponse;
import org.example.naeilbank.domain.notification.NotificationService;
import org.example.naeilbank.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {
    private NotificationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void authenticatedRegisterPreferenceChangeAndRevokeAreOwnerScopedAndPublicKeyOnly() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(owner.toString(), "unused", List.of());
        when(service.publicKey()).thenReturn("public-vapid-key");
        when(service.register(eq(owner), any(SubscriptionRequest.class)))
                .thenReturn(new SubscriptionResponse(subscriptionId, true, null));
        when(service.updateSubscription(eq(owner), eq(subscriptionId), any(SubscriptionRequest.class)))
                .thenReturn(new SubscriptionResponse(subscriptionId, true, null));
        when(service.updatePreference(eq(owner), any(PreferenceRequest.class)))
                .thenReturn(new PreferenceResponse(true, "Asia/Seoul", LocalTime.of(8, 0)));
        doNothing().when(service).revoke(owner, subscriptionId);

        String publicKeyJson = mockMvc.perform(get("/api/v1/notifications/vapid-public-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value("public-vapid-key"))
                .andReturn().getResponse().getContentAsString();
        assertThat(publicKeyJson).doesNotContainIgnoringCase("private");

        String subscriptionJson = """
                {"userId":"%s","endpoint":"https://push.example/sub","keys":{"p256dh":"%s","auth":"%s"}}
                """.formatted(UUID.randomUUID(), publicKey(), authSecret());
        String registerJson = mockMvc.perform(post("/api/v1/notifications/subscriptions")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(subscriptionJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(registerJson)
                .doesNotContain("push.example", publicKey(), authSecret())
                .doesNotContainIgnoringCase("private");

        mockMvc.perform(put("/api/v1/notifications/subscriptions/{id}", subscriptionId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(subscriptionJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subscriptionId.toString()));

        mockMvc.perform(put("/api/v1/notifications/preference")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"timezone\":\"Asia/Seoul\",\"morningTime\":\"08:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(delete("/api/v1/notifications/subscriptions/{id}", subscriptionId)
                        .principal(authentication))
                .andExpect(status().isNoContent());

        verify(service).register(eq(owner), any(SubscriptionRequest.class));
        verify(service).updateSubscription(eq(owner), eq(subscriptionId), any(SubscriptionRequest.class));
        verify(service).updatePreference(eq(owner), any(PreferenceRequest.class));
        verify(service).revoke(owner, subscriptionId);
    }

    @Test
    void rejectsMalformedEndpointAndRequiresExplicitPreferenceFields() throws Exception {
        UUID owner = UUID.randomUUID();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(owner.toString(), "unused", List.of());
        String invalidSubscription = """
                {"endpoint":"http://push.example/sub","keys":{"p256dh":"%s","auth":"%s"}}
                """.formatted(publicKey(), authSecret());

        mockMvc.perform(post("/api/v1/notifications/subscriptions")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidSubscription))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/notifications/preference")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Asia/Seoul\",\"morningTime\":\"08:00\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/notifications/preference")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"timezone\":\"Not/AZone\",\"morningTime\":\"08:00:01\"}"))
                .andExpect(status().isBadRequest());
    }

    private String publicKey() {
        byte[] key = new byte[65];
        key[0] = 4;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private String authSecret() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    }
}
