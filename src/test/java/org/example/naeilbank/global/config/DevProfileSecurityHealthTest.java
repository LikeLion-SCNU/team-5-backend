package org.example.naeilbank.global.config;

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
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("dev")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "management.endpoints.web.exposure.include=health,info",
        "management.endpoint.health.probes.enabled=false",
        "management.health.defaults.enabled=false"
})
class DevProfileSecurityHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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

    @MockBean
    private LedgerQueryRepository ledgerQueryRepository;

    @MockBean
    private MealService mealService;

    @MockBean
    private FaceSimulationRepository faceSimulationRepository;

    @MockBean
    private FaceSimulationOutputRepository faceSimulationOutputRepository;

    @MockBean
    private TransactionTemplate transactionTemplate;

    @Test
    void devProfileHealthEndpointIsPublicWithFixtureConfiguration() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void corsPreflightIncludesGeneratedCorrelationId() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void safeInboundCorrelationIdIsReusedOnSuccessAndPreflight() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("X-Correlation-Id", "trace.valid-123:abc"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "trace.valid-123:abc"));

        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("X-Correlation-Id", "preflight-valid-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "preflight-valid-123"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void unsafeInboundCorrelationIdIsReplacedAndNotEchoed() throws Exception {
        String oversized = "a".repeat(129);

        String invalidHeader = mockMvc.perform(get("/actuator/health")
                        .header("X-Correlation-Id", "bad id with spaces"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn()
                .getResponse()
                .getHeader("X-Correlation-Id");
        String oversizedHeader = mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("X-Correlation-Id", oversized))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andReturn()
                .getResponse()
                .getHeader("X-Correlation-Id");

        assertThat(invalidHeader).isNotEqualTo("bad id with spaces");
        assertThat(oversizedHeader).isNotEqualTo(oversized);
        assertThat(invalidHeader).matches("[0-9a-f-]{36}");
        assertThat(oversizedHeader).matches("[0-9a-f-]{36}");
    }

    @Test
    void otherActuatorEndpointsRemainProtected() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void applicationEndpointsRemainProtected() throws Exception {
        mockMvc.perform(get("/api/internal/protected"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminProbeRequiresAdminRoleAgainstRealHandler() throws Exception {
        String userAccessToken = jwtTokenProvider.createToken(UUID.randomUUID(), "user@example.com", "USER");
        String adminAccessToken = jwtTokenProvider.createToken(UUID.randomUUID(), "admin@example.com", "ADMIN");

        mockMvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + adminAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdminProbeConfiguration {
        @Bean
        AdminProbeController adminProbeController() {
            return new AdminProbeController();
        }
    }

    @RestController
    static class AdminProbeController {
        @GetMapping("/api/admin/probe")
        Map<String, String> probe() {
            return Map.of("status", "ok");
        }
    }
}
