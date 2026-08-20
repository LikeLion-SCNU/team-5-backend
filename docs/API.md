# 내일은행 API 명세 v1

> 기준: 매니패스트 기능명세 v12 + DB 설계서 v1.1 | 모든 경로는 `/api` 프록시 뒤 (프론트는 상대경로 `/api/...`로 호출)
> 인증: `Authorization: Bearer {access_token}` (🔓 표시 = 인증 불필요)
> 에러 형식 통일: `{"error": {"code": "string", "message": "사용자용 한국어 메시지"}}`

## 담당 분배 제안

- **백엔드 A (허찬)**: 인증 · 식사 분개 · 원장/명세서 (§1–3) — 데모 크리티컬 패스
- **백엔드 B (김민수)**: 출처 · 플랜 · 시뮬레이션 · 설정/동의 (§4–7) + 환산 엔진 배치

---

## §1. 인증 (F-YBVSGY)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST 🔓 | `/auth/signup` | 이메일 가입 `{email, password, nickname}` → 201 + 토큰 |
| POST 🔓 | `/auth/login` | 이메일 로그인 `{email, password}` → 토큰 |
| POST 🔓 | `/auth/kakao` | 카카오 로그인 `{code}` (인가코드) → 토큰, 신규면 자동 가입 |
| POST 🔓 | `/auth/refresh` | `{refresh_token}` → 새 토큰 쌍 (기존 리프레시 회전 폐기) |
| POST | `/auth/logout` | `{refresh_token}` → 204, 토큰 폐기 |

토큰 응답 공통:
```json
{ "access_token": "jwt...", "refresh_token": "jwt...", "expires_in": 1800 }
```
규칙: 액세스 30분 / 리프레시 14일(해시 저장·회전). 비밀번호 8자+문자·숫자, bcrypt 해시.

## §2. 식사 사진 분개 (F-SRGOKR)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/meals` | multipart `{photo, record_date}` → 201 `{id, status:"analyzing"}` (AI 분석 시작) |
| GET | `/meals/{id}` | 분석 결과 조회 (아래 예시) |
| POST | `/meals/{id}/items` | 항목 직접 추가 `{food_name, portion}` |
| PATCH | `/meals/{id}/items/{item_id}` | 항목 수정 `{food_name?, portion?}` / 삭제 `{is_deleted: true}` |
| POST | `/meals/{id}/confirm` | **확정** → 항목별 원장 기입 (한 트랜잭션) |
| POST | `/meals/{id}/exclude` | **전체 제외** → 기록만 보관, 원장 미기입 |

GET /meals/{id} 응답:
```json
{
  "id": "uuid", "status": "pending_confirm", "record_date": "2026-08-22",
  "items": [
    {"id": "uuid", "food_name": "현미밥", "portion": "1공기, 약 200g", "est_minutes": 18, "is_deleted": false}
  ],
  "total_est_minutes": -18
}
```
규칙: `confirmed` 이전엔 어떤 값도 원장에 쓰지 않는다. AI 분석은 Gemini Vision 호출.

## §3. 원장 · 명세서 · 잔고 (F-HEZOZV, F-QYZVMY)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/ledger/balance` | 홈 카드용 `{total_minutes, yesterday_net_minutes}` — **v_balance/v_daily_net 뷰만 조회** |
| GET | `/ledger/statements/{date}` | 일별 명세서 (아래 예시) |
| GET | `/ledger/trend?period=7d\|4w` | `[{date, net_minutes}]` 추세 |
| PUT | `/health/daily` | 건강 데이터 upsert `{record_date, sleep_minutes?, steps?, screen_minutes?}` → 환산 배치 트리거 (데모: 시드 주입용) |

GET /ledger/statements/2026-08-22 응답:
```json
{
  "date": "2026-08-22", "net_minutes": 134,
  "entries": [
    {"habit_type": "sleep", "label": "수면 7시간 30분", "minutes_delta": 90, "rule_id": "uuid", "source_id": "uuid"}
  ],
  "protection_mode": false
}
```
규칙: 환산 계수는 conversion_rules 테이블에서 로드(하드코딩 금지). ledger_entries는 append-only. **보호 모드 사용자에겐 음수 필드를 그대로 주되 `protection_mode: true` 플래그 포함 → 표시는 프론트가 중립 처리.**

## §4. 논문 출처 (F-ILQWSY / 관리자 F-XDDAGG)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/sources` · `/api/v1/sources/{id}` | 활성 출처 목록 및 버전 상세 `{logicalKey, versionNumber, title, doiUrl, summaryKo, scopeKo, limitationsKo}` |
| GET | `/api/v1/rules` · `/api/v1/rules/{id}` | 활성 환산 규칙 목록 및 출처가 포함된 버전 상세 |
| GET | `/api/v1/ledger/{entryId}/evidence` | 소유자의 과거 원장 항목에서 당시 `rule_id → source_id` 근거 조회 |
| POST | `/api/admin/sources` · `/api/admin/sources/{id}/versions` | 출처 신규 등록 및 내용 덮어쓰기 없는 새 버전 생성 (관리자) |
| PUT | `/api/admin/sources/{id}/activation` | `expectedVersion` 기반 출처 활성/비활성 전환 (관리자) |
| POST | `/api/admin/rules` · `/api/admin/rules/{id}/versions` | 환산 규칙 신규 등록 및 새 버전 생성 (관리자) |
| PUT | `/api/admin/rules/{id}/activation` | `expectedVersion` 기반 규칙 활성/비활성 전환 (관리자) |

`V4__version_evidence_sources_and_rules.sql`은 개발 DB에 이미 존재하는 출처·규칙 행을
그 자리에서 버전 1로 승격한다. 새 논문이나 환산 계수를 시드하지 않으므로 빈 DB는 빈
카탈로그로 유지되며, 운영 정본 대신 임의 값을 만들지 않는다.

활성 전환은 트랜잭션 커밋 이후 생성되는 신규 계산에만 적용한다. 기존
`ledger_entries.rule_id`는 변경하지 않으며 과거 근거 상세는 비활성 버전도 ID로 계속
조회할 수 있다. 출처 목록은 논리 계보별 최신 활성 버전만 노출하고, 이전 버전은 당시
활성 규칙 및 과거 원장의 근거 해석을 위해 ID 조회를 유지한다.

## §5. 흑자 전환 플랜 (F-IMUMQN)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/plans/current` | 진행 중 플랜 `{title, actions, expected_weekly_minutes, progress_days, status}` |
| POST | `/plans/propose` | AI 플랜 제안 생성 → `{id, title, actions[], expected_weekly_minutes}` |
| POST | `/plans/{id}/respond` | `{action: "accept" \| "reject" \| "replan"}` |

## §6. 얼굴 시뮬레이션 (F-CHCDQW)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/simulations` | multipart `{photo}` → 201 `{id, status:"generating"}` |
| GET | `/simulations/{id}` | `{status: "generating"\|"done"\|"failed", result_current_url, result_improved_url, trend_desc}` |
| DELETE | `/simulations/{id}` | 원본+결과 **하드 삭제** (스토리지 파일까지) + deletion_logs 기록 → 204 |

## §7. 설정 · 보호 모드 · 동의 (F-RBRSJS, F-XIXCTJ, F-OAKCGW)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/users/me` | 프로필+설정 `{email, nickname, notify_enabled, notify_time, protection_mode}` |
| PATCH | `/users/me` | 설정 변경 (보호 모드 수동 on/off 포함) |
| POST | `/protection/respond` | 이상 패턴 제안 응답 `{accept: bool}` (감지 기준: 1시간 내 잔고 조회 10회) |
| GET · PUT | `/consents` · `/consents/{purpose}` | 목적별 동의 조회/변경 `purpose: health_data\|meal_photo\|face_simulation` |
| DELETE | `/users/me/data` | `{targets: ["face_photos"\|"meal_photos"\|"account"]}` 데이터 삭제 요청 |

---

## 공통 규약

1. 날짜는 `YYYY-MM-DD`, 시간값은 **분(minutes) 정수** (프론트에서 "+2시간 14분" 포맷팅)
2. 본인 데이터만 접근 (user_id는 JWT에서 추출 — 요청 바디로 받지 않음)
3. 명세서·잔고는 뷰(v_daily_net/v_balance)만 조회, SUM 재구현 금지
4. FastAPI 자동 문서: 배포 후 `/api/docs`에서 스웨거 확인 가능하게 유지
5. 데모 우선순위: **§1(로그인 생략 가능) → §2 → §3 → §6** 순으로 구현 (§4 GET, §5, §7은 후순위)
