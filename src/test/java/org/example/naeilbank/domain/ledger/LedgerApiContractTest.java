package org.example.naeilbank.domain.ledger;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.naeilbank.controller.LedgerController;
import org.example.naeilbank.domain.ledger.LedgerDtos.BalanceResponse;
import org.example.naeilbank.domain.ledger.LedgerDtos.DailyTrendPoint;
import org.example.naeilbank.domain.ledger.LedgerDtos.LedgerLine;
import org.example.naeilbank.domain.ledger.LedgerDtos.StatementDay;
import org.example.naeilbank.domain.ledger.LedgerDtos.StatementResponse;
import org.example.naeilbank.domain.ledger.LedgerDtos.TrendResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class LedgerApiContractTest {
    private final LedgerQueryService service = mock(LedgerQueryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LedgerController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .registerModule(new JavaTimeModule())
                            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
            .build();
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private final UsernamePasswordAuthenticationToken auth =
            UsernamePasswordAuthenticationToken.authenticated(userId.toString(), "n/a", List.of());

    @Test
    void balanceEndpointReturnsCanonicalTotalsAndPreviousDayDelta() throws Exception {
        when(service.balance(userId)).thenReturn(new BalanceResponse(42, -7,
                LocalDate.of(2026, 8, 20), "Asia/Seoul"));

        mvc.perform(get("/api/v1/ledger/balance").principal(auth))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balanceMinutes").value(42))
                .andExpect(jsonPath("$.previousDayDeltaMinutes").value(-7))
                .andExpect(jsonPath("$.asOfDate").value("2026-08-20"))
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"));

        verify(service).balance(userId);
    }

    @Test
    void statementsEndpointKeepsStablePageAndStatementTextContract() throws Exception {
        LedgerLine line = new LedgerLine(101L, LocalDate.of(2026, 8, 19),
                Instant.parse("2026-08-19T23:55:01Z"), "sleep", 30,
                "입금 30분", "health_daily", UUID.fromString("00000000-0000-0000-0000-000000000101"));
        StatementDay day = new StatementDay(LocalDate.of(2026, 8, 19), 30, 72, -5, List.of(line));
        when(service.statements(userId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20),
                0, 20)).thenReturn(new StatementResponse(LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 20), 0, 20, false, 1, List.of(day)));

        mvc.perform(get("/api/v1/ledger/statements")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-20")
                        .param("page", "0")
                        .param("size", "20")
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].entryDate").value("2026-08-19"))
                .andExpect(jsonPath("$.days[0].dailyNetMinutes").value(30))
                .andExpect(jsonPath("$.days[0].cumulativeBalanceMinutes").value(72))
                .andExpect(jsonPath("$.days[0].previousDayDeltaMinutes").value(-5))
                .andExpect(jsonPath("$.days[0].lines[0].displayText").value("입금 30분"));
    }

    @Test
    void dailyTrendEndpointReturnsFixedSevenDayWindow() throws Exception {
        when(service.dailyTrend(userId, null)).thenReturn(new TrendResponse("daily", List.of(
                new DailyTrendPoint(LocalDate.of(2026, 8, 14), 0, 10),
                new DailyTrendPoint(LocalDate.of(2026, 8, 20), 12, 22))));

        mvc.perform(get("/api/v1/ledger/trends/daily").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("daily"))
                .andExpect(jsonPath("$.points[0].date").value("2026-08-14"))
                .andExpect(jsonPath("$.points[1].netMinutes").value(12));
    }
}
