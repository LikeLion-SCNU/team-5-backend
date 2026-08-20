package org.example.naeilbank.domain.protection;

import org.springframework.stereotype.Component;

@Component
public class ProtectionCopyPolicy {
    public String ledgerLineText(int minutesDelta, boolean protectionMode) {
        long absolute = Math.abs((long) minutesDelta);
        if (minutesDelta > 0) {
            return "입금 " + absolute + "분";
        }
        if (minutesDelta < 0) {
            return protectionMode ? "회복 조정 " + absolute + "분" : "출금 " + absolute + "분";
        }
        return protectionMode ? "회복 유지 0분" : "변동 없음 0분";
    }

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
