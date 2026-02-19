# U-1208 — Preserve local parent mapping until documents load completes

## Context
- 에디터 `문서로 분류` 모드에서 하위 페이지를 만들면 로컬 parent 매핑으로 트리를 유지한다.
- 하지만 새로고침 직후 문서 목록이 아직 비어있는 시점에 sanitize가 먼저 실행되며 parent/favorite 매핑이 지워져 하위 페이지가 루트로 승격된다.

## Goal
- 초기 로딩 단계에서는 sanitize를 실행하지 않도록 보장해 parent 연결관계가 유지되게 한다.

## Scope
- `web-user/src/App.tsx` 문서 목록 로딩 완료 플래그 추가
- sanitize effect를 `documentsLoaded=true` 이후에만 실행
- 워크스페이스 전환 시 로딩 플래그 reset

## Acceptance Criteria
- [x] 하위 페이지 생성 후 새로고침해도 parent-child 연결이 유지된다.
- [x] 로딩 실패 시 기존 로컬 parent/favorite 상태가 덮어써지지 않는다.

## Testing
- `pnpm -C web-user build`

### Result
- [x] `pnpm -C web-user build` 통과
