package org.example.naeilbank.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.model.entity.Plan;
import org.example.naeilbank.domain.plan.PlanDtos;
import org.example.naeilbank.domain.plan.PlanService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanControllerTest {
    private final PlanService service = mock(PlanService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlanController(service)).build();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void ownerCanGenerateAcceptAndPostProgressWithProjectionAndLineage() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID logicalKey = UUID.randomUUID();
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(userId.toString(), null);
        PlanDtos.PlanActionResponse action = new PlanDtos.PlanActionResponse(
                0, "activity", 30, sourceId, ruleId, logicalKey, 3, "per_unit", 10, 3);
        when(service.generate(userId)).thenReturn(response(planId, 0L, Plan.Status.proposed, 0, 30, action));
        when(service.decide(userId, planId, new PlanDtos.PlanDecisionRequest(true, 0L)))
                .thenReturn(response(planId, 1L, Plan.Status.accepted, 0, 30, action));
        when(service.updateProgress(userId, planId,
                new PlanDtos.PlanProgressRequest(LocalDate.parse("2026-08-21"), 10, 1L)))
                .thenReturn(response(planId, 2L, Plan.Status.accepted, 10, 20, action));

        mvc.perform(post("/api/v1/plans/generate").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].sourceId").value(sourceId.toString()))
                .andExpect(jsonPath("$.actions[0].ruleVersion").value(3));
        mvc.perform(post("/api/v1/plans/{id}/decision", planId).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new PlanDtos.PlanDecisionRequest(true, 0L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
        mvc.perform(post("/api/v1/plans/{id}/progress", planId).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(
                                new PlanDtos.PlanProgressRequest(LocalDate.parse("2026-08-21"), 10, 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedMinutes").value(10))
                .andExpect(jsonPath("$.remainingMinutes").value(20));

        verify(service).generate(userId);
    }

    private PlanDtos.PlanResponse response(UUID id, long version, Plan.Status status,
                                           int completed, int remaining, PlanDtos.PlanActionResponse action) {
        return new PlanDtos.PlanResponse(id, version, "Optional deficit repayment plan", status,
                PlanDtos.PlanAvailability.AVAILABLE,
                "This optional plan and its progress are informational and do not change the ledger.",
                -30, 30, 30, LocalDate.parse("2026-08-21"), LocalDate.parse("2026-08-27"),
                completed == 0 ? 0 : 1, completed, remaining, List.of(action));
    }
}
