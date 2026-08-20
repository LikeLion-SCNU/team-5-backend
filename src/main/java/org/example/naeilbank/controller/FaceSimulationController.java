package org.example.naeilbank.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.face.FaceSimulationDtos.CreateRequest;
import org.example.naeilbank.domain.face.FaceSimulationDtos.SimulationResponse;
import org.example.naeilbank.domain.face.FaceSimulationService;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/face-simulations")
@RequiredArgsConstructor
public class FaceSimulationController {
    private final FaceSimulationService faceSimulationService;

    @PostMapping
    public ResponseEntity<SimulationResponse> create(
            Authentication authentication,
            @Valid @RequestBody CreateRequest request
    ) {
        SimulationResponse response = faceSimulationService.create(authenticatedUserId(authentication), request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/v1/face-simulations/" + response.id())
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<SimulationResponse>> list(Authentication authentication) {
        return ResponseEntity.ok(faceSimulationService.list(authenticatedUserId(authentication)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SimulationResponse> get(Authentication authentication, @PathVariable UUID id) {
        return ResponseEntity.ok(faceSimulationService.get(authenticatedUserId(authentication), id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<SimulationResponse> cancel(Authentication authentication, @PathVariable UUID id) {
        return ResponseEntity.ok(faceSimulationService.cancel(authenticatedUserId(authentication), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID id) {
        faceSimulationService.delete(authenticatedUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        try {
            return UUID.fromString(authentication.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new AuthException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
