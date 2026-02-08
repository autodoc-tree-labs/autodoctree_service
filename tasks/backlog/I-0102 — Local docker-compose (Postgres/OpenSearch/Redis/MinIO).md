# I-0102 — Local docker-compose (Postgres/OpenSearch/Redis/MinIO)


## Goal
로컬 인프라를 한 번에 띄워 개발환경을 표준화한다.

## Scope
- docker-compose.yml에 포함:
  - Postgres, OpenSearch(single node), Redis, MinIO
  - healthcheck + volumes
- `.env.example` 제공 (local credentials)
- DEV_SETUP에 포트/계정/버킷 초기화 가이드

## Acceptance Criteria
- `docker compose up -d` 후 모두 healthy
- doc-api가 local profile로 연결 가능(헬스체크/actuator)

## Testing
- CI에서 compose up은 선택, 로컬 체크리스트 문서화

## Security
- local only defaults (prod는 별도)

