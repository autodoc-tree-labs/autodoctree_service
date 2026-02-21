# U-1205 — Tree node direct membership default and descendant toggle

## Context
- 트리 화면에서 상위 노드 선택 시 하위 노드 문서를 자동 합산 표시하면서, 사용자가 문서가 부모/자식 노드에 동시에 분류된 것으로 오해할 수 있다.

## Goal
- 기본 표시를 문서의 직접 소속 노드 기준으로 통일하고, 하위 합산은 명시적 토글로만 보이게 한다.

## Non-goals
- 백엔드 트리 할당 로직 변경
- 스냅샷/멤버십 스키마 변경

## Scope
- 노드 리스트 카운트를 direct membership 기준으로 표시
- 선택 노드 문서 목록을 direct membership 기준으로 표시
- 하위 노드 문서 포함 토글(옵션) 추가

## API / Contracts
- API 변경 없음

## DB / Index changes
- 없음

## Happy path
- 문서는 하나의 노드에서만 기본적으로 보인다.
- 필요 시 토글로 하위 노드 문서를 합산해 볼 수 있다.

## Edge cases
- 하위 노드가 없는 노드에서는 토글이 나타나지 않는다.
- 토글 활성 시 중복 문서는 1회만 표시한다.

## Acceptance Criteria
- [x] 기본 모드에서 문서는 직접 소속 노드에서만 보인다.
- [x] 노드 카운트는 direct membership 기준이다.
- [x] 하위 노드 문서가 있을 때만 토글이 노출된다.

## Testing
- Frontend build:
  - `pnpm -C web-user build`

## Observability
- 추가 로그/메트릭 없음

## Rollout / Rollback
- 롤아웃: web-user 배포 후 즉시 반영
- 롤백: 해당 커밋 revert

## Security / Privacy
- 테넌트 스코프 변경 없음
- 콘텐츠 로그 추가 없음
