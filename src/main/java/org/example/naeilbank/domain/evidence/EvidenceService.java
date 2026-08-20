package org.example.naeilbank.domain.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.audit.AuditAppendService;
import org.example.naeilbank.domain.evidence.EvidenceDtos.*;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.SourceRepository;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.entity.LedgerEntry;
import org.example.naeilbank.entity.Source;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvidenceService {
    private final SourceRepository sourceRepository;
    private final ConversionRuleRepository ruleRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final AuditAppendService auditAppendService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SourceListResponse activeSources() {
        return new SourceListResponse(sourceRepository.findByActiveTrueOrderByTitleAscVersionNumberDesc()
                .stream().map(SourceView::from).toList());
    }

    @Transactional(readOnly = true)
    public SourceView source(UUID sourceId) {
        return SourceView.from(requiredSource(sourceId));
    }

    @Transactional(readOnly = true)
    public RuleListResponse activeRules(ConversionRule.HabitType habitType) {
        var rules = habitType == null
                ? ruleRepository.findByActiveTrueOrderByHabitTypeAscLabelAsc()
                : ruleRepository.findByHabitTypeAndActiveTrueOrderByLabelAsc(habitType);
        return new RuleListResponse(rules.stream().map(this::toRuleView).toList());
    }

    @Transactional(readOnly = true)
    public RuleView rule(UUID ruleId) {
        return toRuleView(requiredRule(ruleId));
    }

    @Transactional(readOnly = true)
    public LedgerEvidenceView ledgerEvidence(UUID userId, long ledgerEntryId) {
        LedgerEntry entry = ledgerRepository.findByIdAndUserId(ledgerEntryId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.EVIDENCE_NOT_FOUND));
        return LedgerEvidenceView.from(entry, toRuleView(requiredRule(entry.getRuleId())));
    }

    @Transactional
    public SourceView createSource(UUID adminId, CreateSourceRequest request) {
        SourceContent content = validated(request.content());
        Instant now = Instant.now(clock);
        Source saved = sourceRepository.saveAndFlush(Source.create(content.title(), content.authors(),
                content.journal(), content.publicationYear(), content.doiUrl(), content.summaryKo(),
                content.scopeKo(), content.limitationsKo(), request.active(), now));
        auditAppendService.appendEvidenceMutation(adminId, "SOURCE_CREATED", saved.getId(),
                saved.getLogicalKey(), saved.getVersionNumber(), saved.isActive(), saved.resourceVersion());
        return SourceView.from(saved);
    }

    @Transactional
    public SourceView versionSource(UUID adminId, UUID sourceId, VersionSourceRequest request) {
        Source previous = lockedSource(sourceId);
        requireVersion(previous.resourceVersion(), request.expectedVersion());
        SourceContent content = validated(request.content());
        int next = sourceRepository.findFirstByLogicalKeyOrderByVersionNumberDesc(previous.getLogicalKey())
                .map(Source::getVersionNumber).orElse(previous.getVersionNumber()) + 1;
        Instant now = Instant.now(clock);
        previous.markVersioned(now);
        sourceRepository.saveAndFlush(previous);
        Source saved = sourceRepository.saveAndFlush(Source.nextVersion(previous, next, content.title(),
                content.authors(), content.journal(), content.publicationYear(), content.doiUrl(),
                content.summaryKo(), content.scopeKo(), content.limitationsKo(), request.active(), now));
        auditAppendService.appendEvidenceMutation(adminId, "SOURCE_VERSIONED", saved.getId(),
                saved.getLogicalKey(), saved.getVersionNumber(), saved.isActive(), saved.resourceVersion());
        return SourceView.from(saved);
    }

    @Transactional
    public SourceView activateSource(UUID adminId, UUID sourceId, ActivationRequest request) {
        Source source = lockedSource(sourceId);
        requireVersion(source.resourceVersion(), request.expectedVersion());
        if (!request.active() && ruleRepository.existsBySourceIdAndActiveTrue(sourceId)) {
            throw new AuthException(ErrorCode.EVIDENCE_SOURCE_IN_USE);
        }
        source.setActive(request.active(), Instant.now(clock));
        Source saved = sourceRepository.saveAndFlush(source);
        auditAppendService.appendEvidenceMutation(adminId, "SOURCE_ACTIVATION_CHANGED", saved.getId(),
                saved.getLogicalKey(), saved.getVersionNumber(), saved.isActive(), saved.resourceVersion());
        return SourceView.from(saved);
    }

    @Transactional
    public RuleView createRule(UUID adminId, CreateRuleRequest request) {
        RuleContent content = validated(request.content());
        Source source = requiredActiveSource(content.sourceId());
        Instant now = Instant.now(clock);
        ConversionRule saved = ruleRepository.saveAndFlush(ConversionRule.create(content.habitType(),
                content.label(), json(content), content.minutesDelta(), content.unit(), source.getId(),
                request.active(), now));
        auditRule(adminId, "RULE_CREATED", saved);
        return toRuleView(saved);
    }

    @Transactional
    public RuleView versionRule(UUID adminId, UUID ruleId, VersionRuleRequest request) {
        ConversionRule previous = lockedRule(ruleId);
        requireVersion(previous.resourceVersion(), request.expectedVersion());
        RuleContent content = validated(request.content());
        requiredActiveSource(content.sourceId());
        int next = ruleRepository.findFirstByLogicalKeyOrderByVersionNumberDesc(previous.getLogicalKey())
                .map(ConversionRule::getVersionNumber).orElse(previous.getVersionNumber()) + 1;
        Instant now = Instant.now(clock);
        previous.markVersioned(now);
        if (request.active()) {
            deactivateRuleFamily(adminId, previous.getLogicalKey());
        } else {
            ruleRepository.saveAndFlush(previous);
        }
        ConversionRule saved = ruleRepository.saveAndFlush(ConversionRule.nextVersion(previous, next,
                content.habitType(), content.label(), json(content), content.minutesDelta(), content.unit(),
                content.sourceId(), request.active(), now));
        auditRule(adminId, "RULE_VERSIONED", saved);
        return toRuleView(saved);
    }

    @Transactional
    public RuleView activateRule(UUID adminId, UUID ruleId, ActivationRequest request) {
        ConversionRule rule = lockedRule(ruleId);
        requireVersion(rule.resourceVersion(), request.expectedVersion());
        if (rule.isActive() == request.active()) {
            return toRuleView(rule);
        }
        if (request.active()) {
            requiredActiveSource(rule.getSourceId());
            deactivateRuleFamily(adminId, rule.getLogicalKey());
        }
        rule.setActive(request.active(), Instant.now(clock));
        ConversionRule saved = ruleRepository.saveAndFlush(rule);
        auditRule(adminId, "RULE_ACTIVATION_CHANGED", saved);
        return toRuleView(saved);
    }

    private SourceContent validated(SourceContent content) {
        validateHttps(content.doiUrl());
        return content;
    }

    private RuleContent validated(RuleContent content) {
        if (!content.condition().isObject() || content.minutesDelta() == 0) {
            throw new AuthException(ErrorCode.INVALID_EVIDENCE_CONTENT);
        }
        return content;
    }

    private void validateHttps(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new AuthException(ErrorCode.INVALID_EVIDENCE_URL);
            }
        } catch (URISyntaxException e) {
            throw new AuthException(ErrorCode.INVALID_EVIDENCE_URL);
        }
    }

    private String json(RuleContent content) {
        try {
            return objectMapper.writeValueAsString(content.condition());
        } catch (JsonProcessingException e) {
            throw new AuthException(ErrorCode.INVALID_EVIDENCE_CONTENT);
        }
    }

    private RuleView toRuleView(ConversionRule rule) {
        try {
            return new RuleView(rule.getId(), rule.getLogicalKey(), rule.getVersionNumber(),
                    rule.getHabitType(), rule.getLabel(), objectMapper.readTree(rule.getConditionJson()),
                    rule.getMinutesDelta(), rule.getUnit(), rule.isActive(), rule.resourceVersion(),
                    rule.getCreatedAt(), rule.getUpdatedAt(), SourceView.from(requiredSource(rule.getSourceId())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored conversion rule condition is invalid", e);
        }
    }

    private void deactivateRuleFamily(UUID adminId, UUID logicalKey) {
        Instant now = Instant.now(clock);
        var activeRules = ruleRepository.findActiveByLogicalKeyForUpdate(logicalKey);
        activeRules.forEach(active -> active.setActive(false, now));
        ruleRepository.saveAllAndFlush(activeRules);
        activeRules.forEach(active -> auditRule(adminId, "RULE_REPLACED", active));
    }

    private void auditRule(UUID adminId, String action, ConversionRule rule) {
        auditAppendService.appendEvidenceMutation(adminId, action, rule.getId(), rule.getLogicalKey(),
                rule.getVersionNumber(), rule.isActive(), rule.resourceVersion());
    }

    private Source requiredSource(UUID id) {
        return sourceRepository.findById(id)
                .orElseThrow(() -> new AuthException(ErrorCode.EVIDENCE_NOT_FOUND));
    }

    private Source lockedSource(UUID id) {
        return sourceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AuthException(ErrorCode.EVIDENCE_NOT_FOUND));
    }

    private Source requiredActiveSource(UUID id) {
        Source source = lockedSource(id);
        if (!source.isActive()) {
            throw new AuthException(ErrorCode.INACTIVE_EVIDENCE_SOURCE);
        }
        return source;
    }

    private ConversionRule requiredRule(UUID id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new AuthException(ErrorCode.EVIDENCE_NOT_FOUND));
    }

    private ConversionRule lockedRule(UUID id) {
        return ruleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AuthException(ErrorCode.EVIDENCE_NOT_FOUND));
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new AuthException(ErrorCode.EVIDENCE_VERSION_CONFLICT);
        }
    }
}
