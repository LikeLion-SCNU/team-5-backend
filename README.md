<div align="center">

# 🦁 되는코드입니다. - Backend

### 멋쟁이사자처럼 순천대학교 14기 - 내일은행

[![Status](https://img.shields.io/badge/상태-개발중-FF7F00?style=flat-square)]()
[![LikeLion](https://img.shields.io/badge/LikeLion--SCNU-14기-FF7F00?style=flat-square)]()
[![Frontend](https://img.shields.io/badge/Frontend-Repo-green?style=flat-square)](https://github.com/LikeLion-SCNU/team-5-frontend)

</div>

---

## 📌 프로젝트 소개

> **내일은행** — 매일의 습관을 논문 근거 기반 '수명 시간'으로 환산해 은행 잔고처럼 보여주는 AI 웰니스 뱅크

점수·뱃지 대신 **수명 시간이라는 진짜 화폐**로 습관의 대가를 보여줍니다.
"오늘 당신 인생에 +2시간 14분이 입금되었습니다."

### 핵심 기능
- 🧾 **수명 입출금 명세서** — 수면·활동·스크린타임·식사를 시간으로 환산, 매일 아침 명세서 발송
- 📷 **식사 사진 분개** — 사진 한 장으로 AI가 음식·음주를 인식해 원장에 기록
- 📚 **논문 출처 태그** — 모든 환산 수치에 근거 논문(BMJ microlife 등) 연결
- 👴 **5년 후 얼굴 시뮬레이션** — 현재 추세 vs 개선 시나리오 비교 생성
- 🛡️ **보호 모드** — 손실 표현을 회복 중심으로 전환하는 안전 가드

## 🛠 기술 스택

| 구분 | 기술 |
|---|---|
| Framework | FastAPI (Python 3.12) |
| Database | PostgreSQL 16 — 원장(append-only) 패턴, 14테이블 |
| Auth | JWT (액세스 30분 / 리프레시 14일) + 카카오 OAuth |
| Infra | Gabia Cloud, Docker Compose, GitHub Actions CI/CD |
| AI | Gemini API (식사 사진 분석), 이미지 생성 API (얼굴 시뮬레이션) |

## 🚀 배포 (자동)

**push만 하면 배포됩니다.** 자세한 내용은 [docs/DEPLOY.md](docs/DEPLOY.md)

| 브랜치 | 환경 | API 주소 | DB |
|---|---|---|---|
| `develop` | 개발 | http://1.201.116.27:8001 | naeil_bank_dev |
| `main` | 운영(심사) | https://1.201.116.27.nip.io/api | naeil_bank |

### 브랜치 규칙
1. 작업은 `develop`에서 (또는 feature 브랜치 → develop PR)
2. **`main` 직접 push 금지** — develop → main PR로만
3. main 머지 = 심사용 서버 반영이므로 develop에서 확인 후 머지

## 🏁 시작하기 (로컬)

```bash
pip install -r requirements.txt
uvicorn main:app --reload   # http://localhost:8000/docs
```

- 현재 `main.py`는 파이프라인 검증용 플레이스홀더 — 실제 구현으로 교체하세요 (Dockerfile 진입점 `uvicorn main:app` 유지 또는 CMD 수정)
- 서버 DB 접속: 배포 환경에서 `DATABASE_URL` 환경변수 자동 주입 (`naeil-db:5432`)
- DB 스키마(14테이블)는 서버에 적용 완료 — 원장 테이블(`ledger_entries`)은 **append-only** (UPDATE/DELETE 트리거 차단), 잔고·명세서 조회는 반드시 `v_daily_net`/`v_balance` 뷰 사용

## 👥 Team 되는코드입니다.

| 이름 | 역할 | GitHub |
|---|---|---|
| 박현빈 | PM · 인프라 | [@Hbin77](https://github.com/Hbin77) |
| 허찬 |  |  |
| 김민수 |  |  |
| 서예슬 |  |  |
| 지뇨쎄 |  |  |
