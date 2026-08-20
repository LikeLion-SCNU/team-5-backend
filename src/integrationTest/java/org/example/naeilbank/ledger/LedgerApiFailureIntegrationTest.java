package org.example.naeilbank.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiFailureIntegrationTest extends LedgerIntegrationSupport {
    @Test
    void ledgerEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/ledger/balance"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedDateReturnsGenericValidationError() throws Exception {
        UUID user = user("malformed-date", false);

        mvc.perform(get("/api/v1/ledger/statements")
                        .param("from", "2026-02-31")
                        .with(authentication(auth(user))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidRangeAndOversizedOffsetAreRejectedBeforeQueries() throws Exception {
        UUID user = user("invalid-page", false);

        mvc.perform(get("/api/v1/ledger/statements")
                        .param("from", "2026-08-20").param("to", "2026-08-19")
                        .with(authentication(auth(user))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEDGER_DATE_RANGE"));

        mvc.perform(get("/api/v1/ledger/statements")
                        .param("page", "19").param("size", "100")
                        .with(authentication(auth(user))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEDGER_PAGE"));
    }
}
