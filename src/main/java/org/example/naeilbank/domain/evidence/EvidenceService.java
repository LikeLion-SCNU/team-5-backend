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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
        return new SourceListResponse(sourceRepository.findLatestActiveVersions()
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
        SourceContent content = EvidencePolicy.validate(request.content());
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
        List<Source> family = lockedSourceFamily(sourceId);
        Source previous = sourceMember(family, sourceId);
        requireVersion(previous.resourceVersion(), request.expectedVersion());
        SourceContent content = EvidencePolicy.validate(request.content());
        int next = family.get(family.size() - 1).getVersionNumber() + 1;
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
        Source source = sourceMember(lockedSourceFamily(sourceId), sourceId);
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
        RuleContent content = EvidencePolicy.validate(request.content());
        Source source = requiredActiveSource(content.sourceId());
        Instant now = Instant.now(clock);
        ConversionRule saved = ruleRepository.saveAndFlush(ConversionRule.create(content.habitType(),
                content.label(), EvidencePolicy.json(objectMapper, content), content.minutesDelta(),
                content.unit(), source.getId(), request.active(), now));
        auditRule(adminId, "RULE_CREATED", saved);
        return toRuleView(saved);
    }

    @Transactional
    public RuleView versionRule(UUID adminId, UUID ruleId, VersionRuleRequest request) {
        RuleContent content = EvidencePolicy.validate(request.content());
        requiredActiveSource(content.sourceId());
        List<ConversionRule> family = lockedRuleFamily(ruleId);
        ConversionRule previous = ruleMember(family, ruleId);
        requireVersion(previous.resourceVersion(), request.expectedVersion());
        int next = family.get(family.size() - 1).getVersionNumber() + 1;
        Instant now = Instant.now(clock);
        previous.markVersioned(now);
        if (request.active()) {
            deactivateRuleFamily(adminId, family);
        } else {
            ruleRepository.saveAndFlush(previous);
        }
        ConversionRule saved = ruleRepository.saveAndFlush(ConversionRule.nextVersion(previous, next,
                content.habitType(), content.label(), EvidencePolicy.json(objectMapper, content),
                content.minutesDelta(), content.unit(), content.sourceId(), request.active(), now));
        auditRule(adminId, "RULE_VERSIONED", saved);
        return toRuleView(saved);
    }

    @Transactional
    public RuleView activateRule(UUID adminId, UUID ruleId, ActivationRequest request) {
        ConversionRule candidate = requiredRule(ruleId);
        if (request.active()) {
            requiredActiveSource(candidate.getSourceId());
        }
        List<ConversionRule> family = lockedRuleFamily(ruleId);
        ConversionRule rule = ruleMember(family, ruleId);
        requireVersion(rule.resourceVersion(), request.expectedVersion());
        if (rule.isActive() == request.active()) {
            return toRuleView(rule);
        }
        if (request.active()) {
            deactivateRuleFamily(adminId, family);
        }
        rule.setActive(request.active(), Instant.now(clock));
        ConversionRule saved = ruleRepository.saveAndFlush(rule);
        auditRule(adminId, "RULE_ACTIVATION_CHANGED", saved);
        return toRuleView(saved);
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

    private void deactivateRuleFamily(UUID adminId, List<ConversionRule> family) {
        Instant now = Instant.now(clock);
        var activeRules = family.stream().filter(ConversionRule::isActive).toList();
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

    private List<Source> lockedSourceFamily(UUID id) {
        List<Source> family = sourceRepository.findFamilyByMemberIdForUpdate(id);
        if (family.isEmpty()) {
            throw new AuthException(ErrorCode.EVIDENCE_NOT_FOUND);
        }
        return family;
    }

    private Source sourceMember(List<Source> family, UUID id) {
        return family.stream().filter(source -> source.getId().equals(id)).findFirst()
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

    private List<ConversionRule> lockedRuleFamily(UUID id) {
        List<ConversionRule> family = ruleRepository.findFamilyByMemberIdForUpdate(id);
        if (family.isEmpty()) {
            throw new AuthException(ErrorCode.EVIDENCE_NOT_FOUND);
        }
        return family;
    }

    private ConversionRule ruleMember(List<ConversionRule> family, UUID id) {
        return family.stream().filter(rule -> rule.getId().equals(id)).findFirst()
                .orElseThrow(() -> new AuthException(ErrorCode.EVIDENCE_NOT_FOUND));
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new AuthException(ErrorCode.EVIDENCE_VERSION_CONFLICT);
        }
    }
}
