package org.example.naeilbank.domain.ledger;

import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
public class LedgerTimezoneResolver {
    static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    ZoneId resolve() {
        return KST_ZONE;
    }
}
