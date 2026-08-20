package org.example.naeilbank.controller;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.evidence.EvidenceDtos.RuleListResponse;
import org.example.naeilbank.domain.evidence.EvidenceDtos.RuleView;
import org.example.naeilbank.domain.evidence.EvidenceService;
import org.example.naeilbank.entity.ConversionRule;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleController {
    private final EvidenceService evidenceService;

    @GetMapping
    public ResponseEntity<RuleListResponse> rules(
            @RequestParam(required = false) ConversionRule.HabitType habitType
    ) {
        return ResponseEntity.ok(evidenceService.activeRules(habitType));
    }

    @GetMapping("/{ruleId}")
    public ResponseEntity<RuleView> rule(@PathVariable UUID ruleId) {
        return ResponseEntity.ok(evidenceService.rule(ruleId));
    }
}
