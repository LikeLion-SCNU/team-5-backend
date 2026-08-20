package org.example.naeilbank.controller;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.evidence.EvidenceDtos.SourceListResponse;
import org.example.naeilbank.domain.evidence.EvidenceDtos.SourceView;
import org.example.naeilbank.domain.evidence.EvidenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sources")
@RequiredArgsConstructor
public class SourceController {
    private final EvidenceService evidenceService;

    @GetMapping
    public ResponseEntity<SourceListResponse> sources() {
        return ResponseEntity.ok(evidenceService.activeSources());
    }

    @GetMapping("/{sourceId}")
    public ResponseEntity<SourceView> source(@PathVariable UUID sourceId) {
        return ResponseEntity.ok(evidenceService.source(sourceId));
    }
}
