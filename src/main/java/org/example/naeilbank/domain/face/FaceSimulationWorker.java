package org.example.naeilbank.domain.face;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.face-simulation.worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class FaceSimulationWorker {
    private static final int MAX_BATCH_SIZE = 10;

    private final FaceSimulationProcessor processor;

    @Scheduled(fixedDelayString = "${app.face-simulation.worker-delay:5000}")
    void processDue() {
        int processed = 0;
        while (processed < MAX_BATCH_SIZE && processor.processOneDue()) {
            processed++;
        }
    }
}
