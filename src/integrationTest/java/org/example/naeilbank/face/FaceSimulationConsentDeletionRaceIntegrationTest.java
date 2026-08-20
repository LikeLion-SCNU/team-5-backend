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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.face-simulation.worker-enabled=false")
class FaceSimulationConsentDeletionRaceIntegrationTest extends FaceSimulationRaceIntegrationSupport {
    @Test
    void consentWithdrawalDuringCurrentCallPreventsImprovedCallAndOutputs() throws Exception {
        Race race = startRace("face-withdraw-race", "withdraw-race");
        try {
            assertThat(jdbcTemplate.update("""
                    update consents set granted = false, revoked_at = now(), version = version + 1
                    where user_id = ? and purpose = 'FACE_AI'
                    """, race.userId())).isOne();
            race.releaseVendor().countDown();

            assertThat(race.processing().get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(race.vendorCalls()).hasValue(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select status from face_simulations where id = ?", String.class, race.simulationId()))
                    .isEqualTo("cancelled");
            assertNoDerivedData(race);
        } finally {
            race.close();
        }
    }

    @Test
    void deletionDuringCurrentCallWinsAndPreventsImprovedCall() throws Exception {
        Race race = startRace("face-delete-race", "delete-race");
        try {
            mockMvc.perform(delete("/api/v1/face-simulations/{id}", race.simulationId())
                            .header(HttpHeaders.AUTHORIZATION, accessToken(race.userId())))
                    .andExpect(status().isNoContent());
            assertThat(simulationCount(race.userId())).isZero();
            race.releaseVendor().countDown();

            assertThat(race.processing().get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(race.vendorCalls()).hasValue(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from media_blobs where user_id = ?", Integer.class, race.userId())).isZero();
        } finally {
            race.close();
        }
    }

    private Race startRace(String userPrefix, String key) throws Exception {
        UUID userId = createUser(userPrefix);
        grantFace(userId);
        UUID simulationId = createSimulation(userId, uploadFaceInput(userId), key);
        byte[] output = png(8, 8);
        CountDownLatch vendorEntered = new CountDownLatch(1);
        CountDownLatch releaseVendor = new CountDownLatch(1);
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
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> processing = executor.submit(processor::processOneDue);
        assertThat(vendorEntered.await(5, TimeUnit.SECONDS)).isTrue();
        return new Race(userId, simulationId, releaseVendor, calls, executor, processing);
    }

    private void assertNoDerivedData(Race race) {
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from face_simulation_outputs where simulation_id = ?",
                Integer.class,
                race.simulationId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from media_blobs
                where user_id = ? and purpose in ('face_output_current', 'face_output_improved')
                """, Integer.class, race.userId())).isZero();
    }

    private record Race(
            UUID userId,
            UUID simulationId,
            CountDownLatch releaseVendor,
            AtomicInteger vendorCalls,
            ExecutorService executor,
            Future<Boolean> processing
    ) {
        void close() {
            releaseVendor.countDown();
            executor.shutdownNow();
        }
    }
}
