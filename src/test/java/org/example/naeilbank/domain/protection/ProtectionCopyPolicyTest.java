package org.example.naeilbank.domain.protection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectionCopyPolicyTest {
    private final ProtectionCopyPolicy policy = new ProtectionCopyPolicy();

    @Test
    void protectedCopyIsRecoverySafeAndDoesNotEmbedNegativeBalance() {
        assertThat(policy.balanceText(true, -42)).doesNotContain("-42").contains("준비");
        assertThat(policy.planTitle(true, "Repay current deficit")).doesNotContain("Repay", "deficit");
        assertThat(policy.morningStatement(true)).doesNotContain("debt", "deficit", "overdue");
    }

    @Test
    void standardCopyPreservesExistingObservableBalanceText() {
        assertThat(policy.balanceText(false, -42)).isEqualTo("현재 잔고: -42분");
    }
}
