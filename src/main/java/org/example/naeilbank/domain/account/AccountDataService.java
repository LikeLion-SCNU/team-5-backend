package org.example.naeilbank.domain.account;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.account.AccountDataDtos.DataCategory;
import org.example.naeilbank.domain.account.AccountDataDtos.DataSummaryResponse;
import org.example.naeilbank.domain.account.AccountDataDtos.DeleteResultResponse;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 데이터 관리 화면의 실제 집계·삭제.
 *
 * 원장(ledger_entries)과 분개(conversion_postings)는 append-only 트리거가 지키므로 지우지 않는다.
 * 대신 개인 식별 가능한 원본(사진·건강 기록·시뮬레이션)을 하드 삭제하고 deletion_logs에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class AccountDataService {
    private final JdbcTemplate jdbc;

    private static final Set<String> SCOPES = Set.of("health", "meal", "face", "notification");

    @Transactional(readOnly = true)
    public DataSummaryResponse summary(UUID userId) {
        List<DataCategory> categories = List.of(
                new DataCategory("meal", "식사 기록",
                        count("select count(*) from meal_records where user_id = ?", userId),
                        mediaBytes(userId, "meal_input")),
                new DataCategory("health", "건강 기록 (수면·걸음·스크린)",
                        count("select count(*) from health_daily where user_id = ?", userId),
                        0L),
                new DataCategory("face", "시뮬레이션 결과",
                        count("select count(*) from face_simulations where user_id = ?", userId),
                        mediaBytes(userId, "face_input") + mediaBytes(userId, "face_output")),
                new DataCategory("notification", "알림 구독·기록",
                        count("select count(*) from web_push_subscriptions where user_id = ?", userId)
                                + count("select count(*) from notification_attempts where user_id = ?", userId),
                        0L)
        );
        long balance = count("select coalesce(sum(minutes_delta), 0) from ledger_entries where user_id = ?", userId);
        long entries = count("select count(*) from ledger_entries where user_id = ?", userId);
        return new DataSummaryResponse(categories, balance, entries);
    }

    @Transactional
    public DeleteResultResponse deleteScopes(UUID userId, List<String> requested) {
        Set<String> scopes = new LinkedHashSet<>();
        for (String raw : requested == null ? List.<String>of() : requested) {
            String scope = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!SCOPES.contains(scope)) {
                throw new AuthException(ErrorCode.VALIDATION_FAILED);
            }
            scopes.add(scope);
        }
        if (scopes.isEmpty()) {
            throw new AuthException(ErrorCode.VALIDATION_FAILED);
        }

        long rows = 0;
        for (String scope : scopes) {
            rows += switch (scope) {
                case "meal" -> deleteMeal(userId);
                case "health" -> deleteHealth(userId);
                case "face" -> deleteFace(userId);
                case "notification" -> deleteNotification(userId);
                default -> 0L;
            };
            log(userId, scope, null);
        }
        return new DeleteResultResponse(new ArrayList<>(scopes), rows,
                "선택한 데이터를 삭제했습니다. 원장 기록은 감사 목적으로 유지됩니다.");
    }

    @Transactional
    public DeleteResultResponse deleteAccount(UUID userId) {
        long rows = deleteMeal(userId) + deleteHealth(userId) + deleteFace(userId) + deleteNotification(userId);
        rows += jdbc.update("delete from consents where user_id = ?", userId);
        rows += jdbc.update("delete from refresh_tokens where user_id = ?", userId);
        rows += jdbc.update("delete from balance_view_events where user_id = ?", userId);
        // protection_events는 불변 트리거(V3 trg_protection_events_immutable)로 보호되는 감사 로그라
        // 삭제하지 않는다 — 계정 익명화로 개인 식별이 제거되므로 정책 문구("감사 목적 유지")와 일치.
        rows += jdbc.update("delete from protection_proposals where user_id = ?", userId);
        rows += jdbc.update("delete from plans where user_id = ?", userId);
        rows += jdbc.update("delete from media_blobs where user_id = ?", userId);
        log(userId, "account", userId);

        // 원장은 append-only라 삭제할 수 없으므로 계정을 익명화해 재로그인을 차단한다.
        jdbc.update("""
                update users
                set email = concat('deleted-', id, '@deleted.timebank'),
                    name = '탈퇴한 사용자',
                    nickname = null,
                    password_hash = null,
                    kakao_id = null,
                    email_verified = false,
                    email_verification_code = null,
                    email_verification_expires_at = null
                where id = ?
                """, userId);
        return new DeleteResultResponse(List.of("account"), rows,
                "계정이 삭제되었습니다. 원장 기록은 익명 처리되어 감사 목적으로만 남습니다.");
    }

    private long deleteMeal(UUID userId) {
        long rows = jdbc.update("""
                delete from meal_items where meal_record_id in
                    (select id from meal_records where user_id = ?)
                """, userId);
        rows += jdbc.update("delete from meal_records where user_id = ?", userId);
        rows += jdbc.update("delete from media_blobs where user_id = ? and purpose = 'meal_input'", userId);
        return rows;
    }

    private long deleteHealth(UUID userId) {
        return jdbc.update("delete from health_daily where user_id = ?", userId);
    }

    private long deleteFace(UUID userId) {
        long rows = jdbc.update("delete from face_simulation_outputs where user_id = ?", userId);
        rows += jdbc.update("delete from face_simulations where user_id = ?", userId);
        rows += jdbc.update(
                "delete from media_blobs where user_id = ? and purpose in ('face_input', 'face_output')", userId);
        return rows;
    }

    private long deleteNotification(UUID userId) {
        long rows = jdbc.update("delete from web_push_subscriptions where user_id = ?", userId);
        rows += jdbc.update("delete from notification_attempts where user_id = ?", userId);
        rows += jdbc.update("delete from notification_preferences where user_id = ?", userId);
        return rows;
    }

    private void log(UUID userId, String targetType, UUID targetId) {
        jdbc.update("insert into deletion_logs (user_id, target_type, target_id) values (?, ?, ?)",
                userId, targetType, targetId);
    }

    private long count(String sql, UUID userId) {
        Long value = jdbc.queryForObject(sql, Long.class, userId);
        return value == null ? 0L : value;
    }

    private long mediaBytes(UUID userId, String purpose) {
        Long value = jdbc.queryForObject(
                "select coalesce(sum(size_bytes), 0) from media_blobs where user_id = ? and purpose = ?",
                Long.class, userId, purpose);
        return value == null ? 0L : value;
    }
}
