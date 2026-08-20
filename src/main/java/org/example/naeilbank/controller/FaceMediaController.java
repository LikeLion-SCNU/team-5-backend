package org.example.naeilbank.controller;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.face.FaceSimulationService;
import org.example.naeilbank.domain.media.MediaModels.MediaDownload;
import org.example.naeilbank.domain.media.MediaModels.MediaMetadata;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/face-media")
@RequiredArgsConstructor
public class FaceMediaController {
    private static final CacheControl PRIVATE_NO_STORE = CacheControl.noStore().cachePrivate();

    private final FaceSimulationService faceSimulationService;

    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> download(Authentication authentication, @PathVariable UUID id) {
        MediaDownload download = faceSimulationService.faceMediaDownload(authenticatedUserId(authentication), id);
        StreamingResponseBody body = download::writeTo;
        return ResponseEntity.ok().headers(representationHeaders(download.metadata())).body(body);
    }

    @RequestMapping(path = "/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(Authentication authentication, @PathVariable UUID id) {
        MediaMetadata metadata = faceSimulationService.faceMediaMetadata(authenticatedUserId(authentication), id);
        return ResponseEntity.ok().headers(representationHeaders(metadata)).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID id) {
        faceSimulationService.deleteFaceMedia(authenticatedUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private HttpHeaders representationHeaders(MediaMetadata metadata) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(metadata.etag());
        headers.setCacheControl(PRIVATE_NO_STORE);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setContentType(MediaType.parseMediaType(metadata.contentType()));
        headers.setContentLength(metadata.sizeBytes());
        return headers;
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
