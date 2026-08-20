package org.example.naeilbank.domain.account;

import java.util.List;

public final class AccountDataDtos {
    private AccountDataDtos() {
    }

    /** 데이터 관리 화면의 항목 하나 — 실제 저장된 건수와 바이트 */
    public record DataCategory(String key, String label, long items, long bytes) {
    }

    public record DataSummaryResponse(
            List<DataCategory> categories,
            long balanceMinutes,
            long ledgerEntries
    ) {
    }

    public record DeleteScopesRequest(List<String> scopes) {
    }

    public record DeleteResultResponse(List<String> deletedScopes, long deletedRows, String message) {
    }
}
