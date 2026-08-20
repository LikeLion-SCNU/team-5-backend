package org.example.naeilbank.controller;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.media.MediaModels.MediaDownload;
import org.example.naeilbank.domain.media.MediaModels.MediaMetadata;
import org.example.naeilbank.domain.media.MediaModels.StoredMedia;
import org.example.naeilbank.domain.media.MediaService;
import org.example.naeilbank.domain.media.MediaUploadPurpose;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaController {
    private static final CacheControl PRIVATE_NO_STORE = CacheControl.noStore().cachePrivate();

    private final MediaService mediaService;

    @PostMapping(path = "/{purpose}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredMedia> upload(
            Authentication authentication,
            @PathVariable String purpose,
            @RequestPart("file") MultipartFile file
    ) {
        StoredMedia stored = mediaService.storeUpload(
                authenticatedUserId(authentication),
                MediaUploadPurpose.parse(purpose),
                file
        );
        return ResponseEntity.status(stored.deduplicated() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/v1/media/" + stored.media().id())
                .body(stored);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> download(
            Authentication authentication,
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        UUID userId = authenticatedUserId(authentication);
        MediaMetadata metadata = mediaService.metadata(userId, id);
        if (etagMatches(ifNoneMatch, metadata.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(cacheHeaders(metadata)).build();
        }
        MediaDownload download = mediaService.download(userId, id);
        HttpHeaders headers = representationHeaders(download.metadata());
        StreamingResponseBody body = download::writeTo;
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @RequestMapping(path = "/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(Authentication authentication, @PathVariable UUID id) {
        MediaMetadata metadata = mediaService.metadata(authenticatedUserId(authentication), id);
        return ResponseEntity.ok().headers(representationHeaders(metadata)).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID id) {
        mediaService.delete(authenticatedUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private HttpHeaders representationHeaders(MediaMetadata metadata) {
        HttpHeaders headers = cacheHeaders(metadata);
        headers.setContentType(MediaType.parseMediaType(metadata.contentType()));
        headers.setContentLength(metadata.sizeBytes());
        return headers;
    }

    private HttpHeaders cacheHeaders(MediaMetadata metadata) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(metadata.etag());
        headers.setCacheControl(PRIVATE_NO_STORE);
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }

    private boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String normalized = candidate.trim();
            if (normalized.startsWith("W/")) {
                normalized = normalized.substring(2).trim();
            }
            if ("*".equals(normalized) || etag.equals(normalized)) {
                return true;
            }
        }
        return false;
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
