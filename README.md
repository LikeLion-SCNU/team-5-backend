<div align="center">

# 🦁 [팀명] - Backend

### 멋쟁이사자처럼 순천대학교 14기 - [프로젝트명]

[![Status](https://img.shields.io/badge/상태-개발중-FF7F00?style=flat-square)]()
[![LikeLion](https://img.shields.io/badge/LikeLion--SCNU-14기-FF7F00?style=flat-square)]()
[![Frontend](https://img.shields.io/badge/Frontend-Repo-green?style=flat-square)](https://github.com/LikeLion-SCNU/team-N-frontend)

</div>

---

## 📌 프로젝트 소개

> 한 줄로 프로젝트를 설명해주세요.

<!-- 프로젝트에 대한 자세한 설명을 작성해주세요 -->

## 👥 팀원

| 이름 | 역할 | GitHub |
|:---:|:---:|:---:|
| 홍길동 | ⚙️ 백엔드 | [@github](https://github.com/) |
| 김철수 | ⚙️ 백엔드 | [@github](https://github.com/) |
| 이영희 | ⚙️ 백엔드 | [@github](https://github.com/) |

## 🛠️ 기술 스택

<!-- 사용하는 기술에 [x] 체크해주세요. 목록에 없으면 직접 추가해도 됩니다! -->

**Framework**

- [ ] ![Django](https://img.shields.io/badge/Django-092E20?style=flat-square&logo=django&logoColor=white)
- [ ] ![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=flat-square&logo=fastapi&logoColor=white)
- [ ] ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white)
- [ ] ![Express](https://img.shields.io/badge/Express-000000?style=flat-square&logo=express&logoColor=white)

**Language**

- [ ] ![Python](https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white)
- [ ] ![Java](https://img.shields.io/badge/Java-007396?style=flat-square&logo=openjdk&logoColor=white)
- [ ] ![Node.js](https://img.shields.io/badge/Node.js-339933?style=flat-square&logo=nodedotjs&logoColor=white)

**Database**

- [ ] ![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white)
- [ ] ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white)
- [ ] ![MongoDB](https://img.shields.io/badge/MongoDB-47A248?style=flat-square&logo=mongodb&logoColor=white)
- [ ] ![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white)
- [ ] ![SQLite](https://img.shields.io/badge/SQLite-003B57?style=flat-square&logo=sqlite&logoColor=white)

**인프라 & 배포**

- [ ] ![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white)
- [ ] ![AWS](https://img.shields.io/badge/AWS-232F3E?style=flat-square&logo=amazonwebservices&logoColor=white)
- [ ] ![Nginx](https://img.shields.io/badge/Nginx-009639?style=flat-square&logo=nginx&logoColor=white)
- [ ] ![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white)

**협업 도구**

- [ ] ![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white)
- [ ] ![Notion](https://img.shields.io/badge/Notion-000000?style=flat-square&logo=notion&logoColor=white)
- [ ] ![Swagger](https://img.shields.io/badge/Swagger-85EA2D?style=flat-square&logo=swagger&logoColor=black)

## 📂 프로젝트 구조

```
📦 backend
├── 📁 config/               ← 설정 파일
├── 📁 apps/                 ← 앱 모듈
│   ├── 📁 users/            ← 사용자 관련
│   ├── 📁 posts/            ← 게시글 관련 (예시)
│   └── ...
├── 📁 docs/                 ← API 문서, 기획서 등
├── .env.example
├── requirements.txt         ← Python 의존성
├── manage.py
└── README.md
```

## 🚀 실행 방법

```bash
# 프로젝트 클론
git clone https://github.com/LikeLion-SCNU/team-N-backend.git

# 가상환경 생성 및 활성화
python -m venv venv
source venv/bin/activate    # Windows: venv\Scripts\activate

# 의존성 설치
pip install -r requirements.txt

# 환경변수 설정
cp .env.example .env
# .env 파일에 DB, 시크릿키 등 입력

# DB 마이그레이션
python manage.py migrate

# 서버 실행
python manage.py runserver
```

## 📡 API 명세

| Method | Endpoint | 설명 |
|:---:|:---|:---|
| GET | `/api/v1/` | API 목록 |
| | | |
| | | |

> 💡 자세한 API 문서는 서버 실행 후 `/docs` 또는 `/swagger`에서 확인 가능 (FastAPI/DRF 기준)

## 🌿 브랜치 전략

| 브랜치 | 용도 |
|:---:|:---|
| `main` | 배포용 (항상 안정된 코드) |
| `develop` | 개발 통합 브랜치 (PR은 여기로) |
| `feat/기능명` | 기능 개발 (예: `feat/user-auth`, `feat/api-orders`) |
| `fix/버그명` | 버그 수정 (예: `fix/db-connection`) |

### 작업 흐름

```
1. develop에서 새 브랜치 생성  →  git checkout -b feat/user-auth develop
2. 작업 후 커밋                →  git add . && git commit -m "feat: 사용자 인증 API 구현"
3. develop으로 PR 생성         →  GitHub에서 PR 생성
4. 코드 리뷰 후 머지           →  PM이 확인 후 머지
5. 배포 시 main으로 머지       →  develop → main
```

## 📅 개발 일정

| 주차 | 기간 | 내용 |
|:---:|:---:|:---|
| 1주차 | MM/DD ~ MM/DD | 기획 및 DB 설계 |
| 2-3주차 | MM/DD ~ MM/DD | 핵심 API 개발 |
| 4-5주차 | MM/DD ~ MM/DD | 프론트 연동 및 고도화 |
| 6주차 | MM/DD ~ MM/DD | 테스트 및 배포 |

## 📎 관련 자료

- [기획서]()
- [API 명세서]()
- [DB ERD]()
- [Frontend Repo](https://github.com/LikeLion-SCNU/team-N-frontend)

---

<div align="center">

**🦁 멋쟁이사자처럼 순천대학교 14기 🦁**

</div>
