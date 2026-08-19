# 배포 파이프라인 (자동)

| 브랜치 | 환경 | 서버 주소 | DB |
|---|---|---|---|
| `develop` | 개발 | http://1.201.116.27:8001 | naeil_bank_dev |
| `main` | 데모/운영 | http://1.201.116.27:8000 | naeil_bank |

- push하면 GitHub Actions가 자동 배포합니다 (SSH 키 필요 없음)
- `main`은 직접 push 금지 — develop → main PR로만
- DB 접속: 컨테이너 네트워크에서 `naeil-db:5432`, 유저 `naeil`, 비밀번호는 서버 환경변수(POSTGRES_PASSWORD)로 자동 주입
- 스키마 14테이블은 이미 적용돼 있음 (원장 append-only 트리거 포함)
- Dockerfile/main.py는 플레이스홀더 — 실제 구현으로 교체하되 `uvicorn main:app` 진입점 또는 Dockerfile CMD를 맞춰 수정
