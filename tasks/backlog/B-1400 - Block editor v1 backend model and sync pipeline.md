# B-1400 — Block editor v1 backend model and sync pipeline

## Context
- 기존 문서 저장 구조는 `body_markdown`/`body_text` 중심이며 블록 기반 편집 모델을 저장할 수 없습니다.
- 검색/임베딩/트리 파이프라인은 `body_text` 입력을 전제로 동작하므로 블록 모델 도입 시 호환 계층이 필요합니다.

## Goal
- `documents.blocks_json` 저장을 도입하고, 저장 시 `body_markdown`/`body_text`를 서버에서 동기화하여 기존 파이프라인을 유지한다.

## Non-goals
- 실시간 협업/CRDT
- 고급 revision diff UI

## Scope
- Flyway migration: `documents.blocks_json` 추가
- 문서 create/get/patch API에서 `blocks_json` round-trip
- `blocks_json` -> `body_markdown`/`body_text` 동기화
- `blocks_json`이 없을 때 markdown 기반 초기 블록 생성(compat)
- `created_by`/`updated_by`/`created_at`/`updated_at` 응답 포함
- 민감 로그 redaction 키 확장(`blocks_json`)

## API / Contracts
- `POST /documents`: `blocks_json` optional input
- `PATCH /documents/{id}`: `blocks_json` optional input
- `GET /documents/{id}`: `blocks_json` 및 작성/수정 메타 포함
- `API_SURFACE.md` 업데이트

## DB / Index changes
- Flyway: `documents.blocks_json JSON NULL`
- 검색 인덱싱 파이프라인은 기존 `body_text` 사용 유지

## Happy path
- EditorV2가 `blocks_json`으로 저장
- 서버가 markdown/plain text 동기화
- 기존 worker/search/tree는 변경 없이 동작

## Edge cases
- malformed `blocks_json`: 400
- `blocks_json` null + markdown only: compat 변환
- content 동기화 실패 시 요청 실패(부분 저장 금지)

## Acceptance Criteria
- [ ] blocks_json round-trip
- [ ] markdown 문서 compat
- [ ] 메타 필드 응답 노출
- [ ] tenant 경계 유지

## Testing
- Unit: blocks<->markdown/plain 변환
- Integration: create/get/patch with blocks_json
- Integration: cross-tenant negative unchanged

## Observability
- 로그 content redaction 유지 (`blocks_json` 추가)
- 기존 request_id/trace_id/workspace_id 로깅 유지

## Rollout / Rollback
- feature flag 기반 프론트 전환
- rollback 시 EditorV1로 즉시 복귀 가능

## Security / Privacy
- workspace scope 강제 유지
- 원문 블록/본문 로그 금지
