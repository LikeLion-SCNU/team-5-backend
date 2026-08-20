package org.example.naeilbank.domain.protection;

import org.springframework.stereotype.Component;

@Component
public class ProtectionCopyPolicy {
    public String balanceText(boolean protectionMode, long balanceMinutes) {
        if (protectionMode) {
            return "Your balance is ready when you are.";
        }
        return "Current balance: " + balanceMinutes + " minutes";
    }

    public String planTitle(boolean protectionMode, String standardTitle) {
        return protectionMode ? "Your next-step plan" : standardTitle;
    }

    public String morningStatement(boolean protectionMode) {
        return protectionMode
                ? "Your morning check-in is ready when you are."
                : "Your morning statement is ready.";
    }
}
