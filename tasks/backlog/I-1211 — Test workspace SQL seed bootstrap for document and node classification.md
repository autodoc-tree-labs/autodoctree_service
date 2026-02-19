# I-1211 — Test workspace SQL seed bootstrap for document and node classification

## Context
- 로컬 검증 시 `Test` 워크스페이스와 다양한 주제 문서를 반복적으로 수동 입력해야 해서 QA/데모 속도가 느립니다.
- 문서 기반 분류(부모-자식 페이지)와 노드 기반 분류(주제 클러스터) 둘 다 재현 가능한 고정 시드가 필요합니다.

## Goal
- SQL 원본 데이터와 Shell 실행 스크립트로 `Test` 워크스페이스 + 다주제 문서를 idempotent하게 초기 주입한다.

## Non-goals
- 프로덕션 데이터 마이그레이션 자동화
- 운영 환경 자동 시드

## Scope
- `scripts/sql/seed_test_workspace.sql` 추가
- `scripts/seed_test_workspace.sh` 추가
- 문서(`README.md`, `docs/DEV_SETUP.md`)에 초기 실행 방법 추가

## API / Contracts
- API 스키마 변경 없음
- 기존 DB 스키마(`users/workspaces/memberships/documents/pipeline_status/outbox_event`)만 사용

## DB / Index changes
- 신규 마이그레이션 없음
- 시드 SQL은 idempotent upsert 방식으로 동작

## Happy path
1. backend를 1회 실행해 기본 owner 계정을 seed한다.
2. `./scripts/seed_test_workspace.sh`를 실행한다.
3. `Test` 워크스페이스와 문서/하위문서가 생성된다.
4. `DocumentSaved` outbox 이벤트가 enqueue되어 파이프라인(ingest→embed→index→tree) 처리가 가능해진다.

## Edge cases
- owner 계정이 없으면 SQL에서 fail-fast로 중단하고 안내 메시지를 출력한다.
- 재실행 시 중복 문서/중복 membership/outbox 중복 enqueue가 발생하지 않는다.

## Acceptance Criteria
- [ ] `Test` 워크스페이스가 생성된다(또는 기존 값 재사용).
- [ ] 다양한 주제 문서가 생성된다.
- [ ] 부모-자식(`parent_document_id`) 관계 문서가 포함된다.
- [ ] 재실행해도 데이터가 폭증하지 않는다(idempotent).
- [ ] README/DEV_SETUP에서 실행 절차를 확인할 수 있다.

## Testing
- Script lint: `bash -n scripts/seed_test_workspace.sh`
- SQL 적용 후 검증 쿼리로 workspace/doc/parent-doc 개수 확인

## Observability
- 스크립트는 생성 결과(workspace id, document count, parent-linked count)만 출력
- 문서 본문/민감 데이터 로그 금지

## Rollout / Rollback
- Rollout: 문서된 명령으로 로컬에서 1회 실행
- Rollback: 해당 workspace(`Test`)를 DB에서 삭제하거나 로컬 DB 볼륨 재생성

## Security / Privacy
- tenant 경계 준수: 한 workspace(`Test`) 범위만 삽입
- 비밀번호/문서 본문 로그 출력 금지
