package org.example.naeilbank.domain.face;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.face-simulation.worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class FaceSimulationWorker {
    private final FaceSimulationService faceSimulationService;

    @Scheduled(fixedDelayString = "${app.face-simulation.worker-delay:5s}")
    void processDue() {
        while (faceSimulationService.processOneDue()) {
            // Drain due work in small transactional claims.
        }
    }
}
