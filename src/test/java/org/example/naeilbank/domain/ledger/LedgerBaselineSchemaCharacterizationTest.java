package org.example.naeilbank.domain.ledger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class LedgerBaselineSchemaCharacterizationTest {
    @Test
    void v1DefinesCanonicalBalanceDailyViewsAndAppendOnlyLedgerTrigger() throws IOException {
        String sql = new String(getClass().getResourceAsStream(
                "/db/migration/V1__canonical_baseline.sql").readAllBytes(), StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE VIEW public.v_balance AS");
        assertThat(sql).contains("sum(minutes_delta) AS total_minutes");
        assertThat(sql).contains("CREATE VIEW public.v_daily_net AS");
        assertThat(sql).contains("sum(minutes_delta) AS net_minutes");
        assertThat(sql).contains("CREATE TRIGGER trg_ledger_no_update BEFORE DELETE OR UPDATE");
        assertThat(sql).contains("CREATE INDEX idx_ledger_user_date ON public.ledger_entries");
    }
}
