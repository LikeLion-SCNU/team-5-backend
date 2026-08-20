package org.example.naeilbank;

import org.example.naeilbank.domain.auth.repository.RefreshTokenRepository;
import org.example.naeilbank.domain.conversion.ConversionPostingRepository;
import org.example.naeilbank.domain.model.repository.AuditEventRepository;
import org.example.naeilbank.domain.model.repository.ConsentRepository;
import org.example.naeilbank.domain.model.repository.ConversionRuleRepository;
import org.example.naeilbank.domain.model.repository.HealthDailyRepository;
import org.example.naeilbank.domain.model.repository.LedgerEntryRepository;
import org.example.naeilbank.domain.model.repository.MealItemRepository;
import org.example.naeilbank.domain.model.repository.MediaBlobRepository;
import org.example.naeilbank.domain.model.repository.SourceRepository;
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

    @Test
    void contextLoads() {
    }
}
