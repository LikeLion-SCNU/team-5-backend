package org.example.naeilbank.domain.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionInput;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.domain.conversion.ConversionModels.RuleTerms;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.SourceRepository;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.entity.LedgerEntry;
import org.example.naeilbank.entity.Source;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversionService {
    private final UserRepository userRepository;
    private final ConversionRuleRepository ruleRepository;
    private final SourceRepository sourceRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final ConversionPostingRepository postingRepository;
    private final ConversionSourceEventGuard sourceEventGuard;
    private final ExactConversionEngine engine;
    private final ConversionSnapshotFactory snapshots;
    private final Clock clock;

    @Autowired
    public ConversionService(UserRepository userRepository,
                             ConversionRuleRepository ruleRepository,
                             SourceRepository sourceRepository,
                             LedgerEntryRepository ledgerRepository,
                             ConversionPostingRepository postingRepository,
                             ConversionSourceEventGuard sourceEventGuard,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this(userRepository, ruleRepository, sourceRepository, ledgerRepository, postingRepository,
                sourceEventGuard, new ExactConversionEngine(), objectMapper, clock);
    }

    ConversionService(UserRepository userRepository,
                      ConversionRuleRepository ruleRepository,
                      SourceRepository sourceRepository,
                      LedgerEntryRepository ledgerRepository,
                      ConversionPostingRepository postingRepository,
                      ConversionSourceEventGuard sourceEventGuard,
                      ExactConversionEngine engine,
                      ObjectMapper objectMapper,
                      Clock clock) {
        this.userRepository = userRepository;
        this.ruleRepository = ruleRepository;
        this.sourceRepository = sourceRepository;
        this.ledgerRepository = ledgerRepository;
        this.postingRepository = postingRepository;
        this.sourceEventGuard = sourceEventGuard;
        this.engine = engine;
        this.snapshots = new ConversionSnapshotFactory(objectMapper);
        this.clock = clock;
    }

    @Transactional
    public ConversionReceipt convert(UUID userId, ConversionCommand command) {
        command = bounded(command);
        String requestHash = snapshots.fingerprint(command);
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        Optional<ConversionPosting> replay = postingRepository
                .findByUserIdAndSourceEventTypeAndSourceEventIdAndHabitType(userId,
                        command.sourceType().persistedValue(), command.sourceEventId(),
                        command.category().persistedValue().name());
        if (replay.isPresent()) {
            if (!replay.get().matches(requestHash)) {
                throw new AuthException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return replay.get().receipt(true);
        }

        sourceEventGuard.requireOwned(userId, command);

        ConversionRule rule = uniqueRule(command);
        Source source = sourceRepository.findByIdAndActiveTrueForShare(rule.getSourceId())
                .orElseThrow(() -> new AuthException(ErrorCode.CONVERSION_RULE_UNAVAILABLE));
        snapshots.requireUnconditional(rule);
        ConversionUnit persistedUnit = ConversionUnit.parse(rule.getUnit());
        var result = engine.calculate(new ConversionInput(command.category(), command.unit(), command.value()),
                new RuleTerms(HabitCategory.from(rule.getHabitType()), persistedUnit,
                        rule.getMinutesDelta(), rule.isActive(), source.isActive()));
        Instant now = Instant.now(clock);
        LedgerEntry ledger = ledgerRepository.saveAndFlush(LedgerEntry.posted(userId,
                command.entryDate(), rule.getHabitType(), result.ledgerMinutes(), rule.getId(),
                command.sourceType().persistedValue(), command.sourceEventId(), now));
        ConversionPosting posting = ConversionPosting.create(userId, command, rule, source, result,
                ledger.getId(), requestHash, snapshots.snapshots(command, rule, source, result), now);
        return postingRepository.saveAndFlush(posting).receipt(false);
    }

    private ConversionCommand bounded(ConversionCommand command) {
        snapshots.requireShape(command);
        return new ConversionCommand(command.sourceEventId(), command.sourceType(), command.category(),
                command.unit(), engine.requireBoundedInput(command.value()), command.entryDate());
    }

    private ConversionRule uniqueRule(ConversionCommand command) {
        List<ConversionRule> rules = ruleRepository.findActiveForConversion(
                command.category().persistedValue(), command.unit().persistedValue());
        if (rules.isEmpty()) {
            rules = ruleRepository.findActiveForConversion(
                    command.category().persistedValue(), command.unit().persistedValue());
        }
        if (rules.isEmpty()) {
            throw new AuthException(ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        }
        if (rules.size() != 1) {
            throw new AuthException(ErrorCode.CONVERSION_RULE_AMBIGUOUS);
        }
        return rules.get(0);
    }
}
