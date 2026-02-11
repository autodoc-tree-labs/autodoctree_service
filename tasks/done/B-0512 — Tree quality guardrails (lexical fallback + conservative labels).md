# B-0512 — Tree quality guardrails (lexical fallback + conservative labels)

## Context
- 현재 로컬 환경 임베딩(`local-stub-v1`)은 의미 기반 유사도를 충분히 반영하지 못해, 서로 무관한 문서가 같은 클러스터로 묶이는 경우가 발생한다.
- 라벨러가 상위 토큰 3개를 단순 연결해 `연구-축구-문학` 같은 과도한 혼합 라벨을 생성한다.

## Goal
- 로컬 개발 환경에서 트리 품질을 즉시 개선하기 위해 유사도 계산에 lexical fallback을 적용하고 라벨을 보수적으로 생성한다.

## Non-goals
- API 엔드포인트 추가/변경 없음.
- 테넌트 격리/권한 모델 변경 없음.

## Scope
- NeighborBuilder에 TF-IDF 기반 lexical similarity fallback 추가.
- `local-stub` 임베딩 모델은 그래프 생성 시 신뢰하지 않고 lexical 유사도 우선 적용.
- TreeLabeler 라벨 생성을 `대표 1개 + 조건부 보조 1개`로 제한.
- 숫자/노이즈 토큰 필터 강화.
- (구조 안정화) top-level과 leaf 라벨이 동일한 경우 불필요한 중복 leaf 생성 회피.
- 단위 테스트 추가/수정.

## Acceptance Criteria
- [ ] 무관 문서 혼합 라벨 빈도 감소.
- [ ] 라벨 길이/복잡도 감소(과도한 하이픈 결합 방지).
- [ ] 기존 트리 API 계약 유지.
- [ ] `TreeAlgorithmsTest` 통과.

## Testing
- `./gradlew -p services :doc-api:test --tests "com.autodoctree.api.domain.TreeAlgorithmsTest"`
- 수동: 트리 재빌드 후 노드 라벨/클러스터 품질 확인.

## Rollout / Rollback
- 백엔드 배포 후 재빌드로 반영.
- 이슈 시 해당 커밋 롤백.
