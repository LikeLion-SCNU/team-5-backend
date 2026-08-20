# 배포 파이프라인 (자동)

| 브랜치 | 환경 | 서버 주소 | DB |
|---|---|---|---|
| `develop` | 개발 | http://1.201.116.27:8001 | naeil_bank_dev |
| `main` | 데모/운영 | https://timebank.hbinserver.cloud/api (직접: :8000) | naeil_bank |

- push하면 GitHub Actions가 자동 배포합니다 (SSH 키 필요 없음)
- `main`은 직접 push 금지 — develop → main PR로만
- DB 접속: 컨테이너 네트워크에서 `naeil-db:5432`, 유저 `naeil`, 비밀번호는 서버 환경변수(POSTGRES_PASSWORD)로 자동 주입
- 스키마 14테이블은 이미 적용돼 있음 (원장 append-only 트리거 포함)
- 배포 이미지는 Java 21 기반 Spring Boot jar를 멀티스테이지 Dockerfile로 빌드한다. 컨테이너 내부 포트는 `8080`이며, compose는 `develop`을 외부 `8001`, `main`을 외부 `8000`에 매핑한다.
- GitHub Actions는 SSH 전에 `./gradlew --no-daemon clean test integrationTest bootJar`, `docker compose config`, `docker build`를 실행한다.
- 원격 배포는 `scripts/deploy/remote-deploy.sh`가 SHA 태그 이미지로 후보 컨테이너를 띄우고 `/actuator/health/readiness`를 확인한다. 실패하면 이전 정상 이미지/설정으로 롤백한다.
