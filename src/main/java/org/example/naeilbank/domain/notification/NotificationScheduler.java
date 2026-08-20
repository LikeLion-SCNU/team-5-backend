package org.example.naeilbank.domain.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationScheduler {
    private final NotificationService notificationService;

    @Scheduled(cron = "0 * * * * *")
    public void enqueueMorningStatements() {
        notificationService.enqueueDueMorningStatements();
    }

    @Scheduled(fixedDelayString = "PT30S")
    public void processAttempts() {
        notificationService.processDueAttempts();
    }
}
