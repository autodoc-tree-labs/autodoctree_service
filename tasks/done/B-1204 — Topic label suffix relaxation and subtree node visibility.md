# B-1204 — Topic label suffix relaxation and subtree node visibility

## Context
- Topic 트리에서 소규모 클러스터가 `clusterSize < 2` 조건만으로 `*-기타`로 강제 라벨링되어, 사용자 입장에서 `녹차` 대신 `녹차-기타`가 과도하게 생성된다.
- UI는 노드 카운트/문서 목록을 선택 노드의 direct membership만 보여주므로, 상위 노드를 선택했을 때 하위 노드 문서가 있어도 0건으로 보인다.

## Goal
- 소규모지만 의미 있는 단일 문서 클러스터는 기본 라벨(`녹차`)을 유지하고, 트리 UI에서 상위 노드 선택 시 하위 노드 문서가 함께 보이도록 한다.

## Non-goals
- 트리 클러스터링 알고리즘(엣지/커뮤니티) 자체 변경
- 잠금 정책 변경

## Scope
- `TreeLabeler.finalizeLabel`의 `-기타` 강제 조건 완화
- 관련 단위 테스트 추가
- `web-user` 트리 화면의 노드 카운트 및 선택 문서 계산을 하위 노드 합산 기준으로 개선

## API / Contracts
- API 스키마 변경 없음

## DB / Index changes
- 없음

## Happy path
- 단일 문서 클러스터가 생성되어도 라벨이 `녹차`로 유지된다.
- `녹차` 상위 노드를 선택하면 하위(`녹차-기타` 등) 문서가 목록에 표시된다.

## Edge cases
- 하위 노드가 없는 leaf 노드는 기존과 동일하게 동작한다.
- 하위 노드에 중복 문서가 있더라도 UI에서는 중복 없이 표시한다.

## Acceptance Criteria
- [x] `clusterSize == 1`, `qualityScore` 정상인 경우 `-기타` 강제 라벨이 붙지 않는다.
- [x] 트리 노드 카운트가 하위 노드 합산 기준으로 표시된다.
- [x] 상위 노드 선택 시 하위 노드 문서가 우측 목록에 보인다.

## Testing
- Unit tests:
  - `./gradlew -p services :doc-api:test --tests "com.autodoctree.api.domain.TreeLabelingChainTest"`
  - `./gradlew -p services :doc-api:test`
- Frontend build:
  - `pnpm -C web-user build`

## Observability
- 기존 라벨링/트리 메트릭 유지

## Rollout / Rollback
- 롤아웃: 백엔드/프론트 배포 후 트리 재빌드로 즉시 확인 가능
- 롤백: 해당 커밋 revert

## Security / Privacy
- 문서 원문/본문 로그 추가 없음
- tenant scope 로직 변경 없음
