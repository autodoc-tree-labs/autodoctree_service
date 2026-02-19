# B-1206 — Persist document hierarchy parent in backend

## Context
- 에디터에서 하위 페이지를 만들어도 parent-child 관계가 프론트 로컬 상태에만 저장된다.
- 새 브라우저/새 기기/일부 로딩 타이밍에서 하위 페이지가 루트 페이지로 보이는 문제가 발생한다.

## Goal
- 문서 계층 관계를 DB/백엔드에 영구 저장해 새로고침/재접속 이후에도 일관된 parent-child 구조를 유지한다.

## Scope
- DB migration: `documents.parent_document_id` 컬럼 추가
- 백엔드:
  - `POST /documents`에서 `parent_document_id` 저장 지원
  - `GET /documents`, `GET /documents/{id}`에서 `parent_document_id` 응답
  - 부모 문서는 동일 workspace에서만 허용 (tenant-safe validation)
  - 부모 문서 삭제 시 자식 문서는 `parent_document_id = null`로 안전 승격
- 프론트:
  - 에디터 문서 트리의 parent 결정 시 서버 `parent_document_id` 우선 사용
  - 하위 페이지 생성 시 `parent_document_id`를 서버로 전송

## Acceptance Criteria
- [x] 하위 페이지 생성 후 새로고침/재로그인해도 parent-child 관계가 유지된다.
- [x] 다른 workspace 문서를 parent로 지정하는 시도는 거부된다.
- [x] 부모 삭제 시 자식 문서는 루트로 승격된다.

## Testing
- `./gradlew -p services :doc-api:test --tests \"*DocumentHierarchyIntegrationTest\"`
- `pnpm -C web-user build`

### Result
- [x] `./gradlew -p services :doc-api:test --tests \"*DocumentHierarchyIntegrationTest\"` 통과
- [x] `pnpm -C web-user build` 통과
