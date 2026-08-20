package org.example.naeilbank;

import org.example.naeilbank.domain.auth.repository.RefreshTokenRepository;
import org.example.naeilbank.domain.conversion.ConversionPostingRepository;
import org.example.naeilbank.domain.ledger.LedgerQueryRepository;
import org.example.naeilbank.domain.model.repository.AuditEventRepository;
import org.example.naeilbank.domain.model.repository.ConsentRepository;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.FaceSimulationOutputRepository;
import org.example.naeilbank.domain.model.repository.FaceSimulationRepository;
import org.example.naeilbank.domain.model.repository.HealthDailyRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.MealItemRepository;
import org.example.naeilbank.domain.model.repository.MediaBlobRepository;
import org.example.naeilbank.domain.model.repository.SourceRepository;
import org.example.naeilbank.domain.meal.MealService;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class NaeilBankBackApplicationTests {

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockBean
    private ConsentRepository consentRepository;

    @MockBean
    private AuditEventRepository auditEventRepository;

    @MockBean
    private MediaBlobRepository mediaBlobRepository;

    @MockBean
    private FaceSimulationRepository faceSimulationRepository;

    @MockBean
    private FaceSimulationOutputRepository faceSimulationOutputRepository;

    @MockBean
    private SourceRepository sourceRepository;

    @MockBean
    private ConversionRuleRepository conversionRuleRepository;

    @MockBean
    private LedgerEntryRepository ledgerEntryRepository;

    @MockBean
    private ConversionPostingRepository conversionPostingRepository;

    @MockBean
    private HealthDailyRepository healthDailyRepository;

    @MockBean
    private MealItemRepository mealItemRepository;

    @MockBean
    private LedgerQueryRepository ledgerQueryRepository;

    @MockBean
    private MealService mealService;

    @MockBean
    private TransactionTemplate transactionTemplate;

    @Test
    void contextLoads() {
    }
}
