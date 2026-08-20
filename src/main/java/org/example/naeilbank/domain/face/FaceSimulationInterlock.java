package org.example.naeilbank.domain.face;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FaceSimulationInterlock {
    private final Set<CancellationKey> cancellationRequests = ConcurrentHashMap.newKeySet();

    public void requestCancellation(UUID simulationId, UUID userId) {
        cancellationRequests.add(new CancellationKey(simulationId, userId));
    }

    public boolean isCancellationRequested(UUID simulationId, UUID userId) {
        return cancellationRequests.contains(new CancellationKey(simulationId, userId));
    }

    public void releaseCancellation(UUID simulationId, UUID userId) {
        cancellationRequests.remove(new CancellationKey(simulationId, userId));
    }

    private record CancellationKey(UUID simulationId, UUID userId) {
    }
}
