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
}
