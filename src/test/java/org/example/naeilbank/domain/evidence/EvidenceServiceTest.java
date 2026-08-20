package org.example.naeilbank.domain.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.audit.AuditAppendService;
import org.example.naeilbank.domain.evidence.EvidenceDtos.CreateRuleRequest;
import org.example.naeilbank.domain.evidence.EvidenceDtos.RuleContent;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.SourceRepository;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.entity.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvidenceServiceTest {
    @Mock
    SourceRepository sourceRepository;
    @Mock
    ConversionRuleRepository ruleRepository;
    @Mock
    LedgerEntryRepository ledgerRepository;
    @Mock
    AuditAppendService auditAppendService;
    @Mock
    Source source;

    EvidenceService evidenceService;

    @BeforeEach
    void setUp() {
        evidenceService = new EvidenceService(
                sourceRepository,
                ruleRepository,
                ledgerRepository,
                auditAppendService,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void activeRuleCreationLocksSourceBeforeCheckingItsState() {
        UUID sourceId = UUID.randomUUID();
        when(sourceRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(source.getId()).thenReturn(sourceId);
        when(source.isActive()).thenReturn(true);
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(ruleRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        evidenceService.createRule(
                UUID.randomUUID(),
                new CreateRuleRequest(
                        new RuleContent(
                                ConversionRule.HabitType.activity,
                                "활동 규칙",
                                new ObjectMapper().createObjectNode().put("minimum", 1),
                                10,
                                "per_day",
                                sourceId
                        ),
                        true
                )
        );

        verify(sourceRepository).findByIdForUpdate(sourceId);
        verify(sourceRepository).findById(sourceId);
        verify(sourceRepository, never()).delete(any());
    }
}
