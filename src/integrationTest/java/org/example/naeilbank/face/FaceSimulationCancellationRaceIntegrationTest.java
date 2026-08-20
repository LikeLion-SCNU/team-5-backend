package org.example.naeilbank.face;

import org.example.naeilbank.domain.model.entity.FaceSimulationOutput;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.naeilbank.face.FaceIntegrationFixtures.png;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.face-simulation.worker-enabled=false")
class FaceSimulationCancellationRaceIntegrationTest extends FaceSimulationRaceIntegrationSupport {
    @Test
    void completedCancellationPreventsImprovedCallAndOutputs() throws Exception {
        UUID userId = createUser("face-cancel-race");
        grantFace(userId);
        UUID simulationId = createSimulation(userId, uploadFaceInput(userId), "cancel-race");
        byte[] output = png(8, 8);
        CountDownLatch vendorEntered = new CountDownLatch(1);
        CountDownLatch releaseVendor = new CountDownLatch(1);
        AtomicInteger vendorCalls = blockingCurrent(output, vendorEntered, releaseVendor);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> processing = executor.submit(processor::processOneDue);
            assertThat(vendorEntered.await(5, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/api/v1/face-simulations/{id}/cancel", simulationId)
                            .header(HttpHeaders.AUTHORIZATION, accessToken(userId)))
                    .andExpect(status().isOk());
            assertThat(jdbcTemplate.queryForObject(
                    "select status from face_simulations where id = ?", String.class, simulationId))
                    .isEqualTo("cancelled");
            releaseVendor.countDown();

            assertThat(processing.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(vendorCalls).hasValue(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from face_simulation_outputs where simulation_id = ?",
                    Integer.class,
                    simulationId
            )).isZero();
        } finally {
            releaseVendor.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void crossOwnerCancellationCannotInterruptGeneration() throws Exception {
        UUID ownerId = createUser("face-race-owner");
        UUID attackerId = createUser("face-race-attacker");
        grantFace(ownerId);
        UUID simulationId = createSimulation(ownerId, uploadFaceInput(ownerId), "cross-owner-race");
        byte[] output = png(8, 8);
        CountDownLatch vendorEntered = new CountDownLatch(1);
        CountDownLatch releaseVendor = new CountDownLatch(1);
        AtomicInteger vendorCalls = blockingCurrent(output, vendorEntered, releaseVendor);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> processing = executor.submit(processor::processOneDue);
            assertThat(vendorEntered.await(5, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(post("/api/v1/face-simulations/{id}/cancel", simulationId)
                            .header(HttpHeaders.AUTHORIZATION, accessToken(attackerId)))
                    .andExpect(status().isNotFound());
            releaseVendor.countDown();

            assertThat(processing.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(vendorCalls).hasValue(2);
            assertThat(jdbcTemplate.queryForObject(
                    "select status from face_simulations where id = ?", String.class, simulationId))
                    .isEqualTo("done");
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from face_simulation_outputs where simulation_id = ?",
                    Integer.class,
                    simulationId
            )).isEqualTo(2);
        } finally {
            releaseVendor.countDown();
            executor.shutdownNow();
        }
    }

    private AtomicInteger blockingCurrent(
            byte[] output,
            CountDownLatch vendorEntered,
            CountDownLatch releaseVendor
    ) {
        AtomicInteger calls = new AtomicInteger();
        when(imageGenerator.generate(any(), any(), any())).thenAnswer(invocation -> {
            FaceSimulationOutput.Label label = invocation.getArgument(2);
            calls.incrementAndGet();
            if (label == FaceSimulationOutput.Label.current) {
                vendorEntered.countDown();
                assertThat(releaseVendor.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return generated(label, output);
        });
        return calls;
    }
}
