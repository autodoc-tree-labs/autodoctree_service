# AutoDoc Tree (Monorepo)
“저장/업로드만 하면 알아서 트리로 정리되는” 문서 서비스 — **Workspace = Tenant 멀티테넌시**

이 레포는 **모노레포**로 운영합니다.

- `services/` : Kotlin + Spring Boot (API + Workers)
- `web-user/` : 사용자 웹 (Vite + React + TS)
- `web-admin/` : 운영/관리자 웹 (Vite + React + TS)
- `tasks/backlog/` : 티켓 기반 개발(프론트/백엔드/인프라 포함)

## Goals (v1 GA)
- Web에서 문서 작성(Markdown) + 파일 업로드(PDF/DOCX/TXT/MD)
- 비동기 파이프라인: ingest → embedding → index → auto-tree snapshot
- 가상 트리(스냅샷): 2-depth 기본, recommended/apply, lock(고정)
- Explain(왜 여기에?) + Drag&Drop 피드백(move/rename) + 개인화 v1(간단 규칙/가중치)
- 멀티테넌시 격리: API/DB/OpenSearch/Redis/S3까지 **fail-closed**
- 운영: 재처리/감사로그/관측성/런북

## Ports (local)
- `web-admin`: 5173
- `web-user`: 5174
- `doc-api`: 8080 (권장)
- (선택) OpenSearch: 59200, Redis: 56379, Postgres: 5432, MinIO: 59000/59001

## Quickstart

1) infra
```bash
docker compose up -d
docker compose ps
```

2) backend (IntelliJ)
- `services/doc-api` Run Config (`com.autodoctree.api.DocApiApplicationKt`)
- 기본 포트 기준 추가 env 없이 실행 가능

3) front
```bash
pnpm -w install
pnpm -C web-user dev --port 5174
pnpm -C web-admin dev --port 5173
```

4) tests/build
```bash
./gradlew -p services test
pnpm build
pnpm test:e2e
```

## Documents
- `ARCHITECTURE.md` : 시스템/데이터플로우/멀티테넌시 모델
- `API_SURFACE.md` : REST API v1 초안
- `Plans.md` : 마일스톤 + 티켓 인덱스
- `AGENTS.md` : 개발 규칙(테넌시/관측/테스트/PR)

## Ticket Prefix
- `B-xxxx` Backend (services)
- `U-xxxx` Web User
- `A-xxxx` Web Admin
- `I-xxxx` Infra/Platform

> 모든 구현은 `tasks/backlog/*.md` 티켓에서 시작합니다.
