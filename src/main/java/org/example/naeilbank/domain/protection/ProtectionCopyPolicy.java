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
            return "잔고는 마음이 준비되었을 때 확인하실 수 있어요.";
        }
        return "현재 잔고: " + balanceMinutes + "분";
    }

    public String planTitle(boolean protectionMode, String standardTitle) {
        return protectionMode ? "나의 다음 걸음 플랜" : standardTitle;
    }

    public String morningStatement(boolean protectionMode) {
        return protectionMode
                ? "아침 체크인이 준비되어 있어요. 편할 때 확인해 주세요."
                : "아침 명세서가 도착했습니다.";
    }
}
