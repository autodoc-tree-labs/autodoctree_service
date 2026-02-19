# B-1202 — Tree Rebuild Quality Hardening (similarity scale, edge policy, clustering guardrails)

## Context
- neighbor normalize 스케일에서 낮은 min similarity(0.25)로 무관 문서 엣지가 과다 생성되어 과연결/브릿지 혼합이 발생한다.
- 운영 튜닝 시 tree_rebuild_summary에 분포/필터 단계 통계가 부족해 원인 추적이 어렵다.

## Goal
- 리빌드 품질을 운영형으로 고도화해 과연결을 줄이고, 계측 기반 튜닝이 가능하도록 한다.

## Non-goals
- 문서 수집/임베딩 파이프라인 구조 변경
- 멀티뷰 정책/권한 모델 변경

## Scope
- NeighborBuilder 계측 확장: similarity 분포, filter 단계별 제거 수, degree 통계
- min similarity 스케일 정리: 기본값 상향 + auto threshold(옵션)
- edge policy 강화: mutual-kNN, SNN, degree-cap bridge prune
- cluster 후처리 강화: small merge affinity threshold, oversized split retry with higher resolution
- Tree summary/log payload 확장 및 config echo 강화
- 단위 테스트/회귀 테스트 추가
- README/DEV_SETUP/.env.example/application.yml 동기화

## API / Contracts
- 외부 REST 계약 변경 없음
- `tree_rebuild_summary` 로그 필드 확장(숫자 통계 중심)

## DB / Index changes
- 없음

## Happy path
1. 리빌드 시 그래프 후보 생성
2. minSimilarity/topK/mutual/SNN/degree-cap 순으로 필터링
3. 클러스터링 + merge/split guardrail 적용
4. 분포/단계별 제거 수/degree 통계가 summary 로그로 출력

## Edge cases
- 샘플 수가 적은 경우 분포 통계가 0/NaN 없이 안전하게 출력
- auto threshold가 사용자 min threshold보다 낮으면 사용자 값 우선
- bridge prune 후에도 deterministic 결과 유지

## Acceptance Criteria
- [x] tree_rebuild_summary에 similarity 분포/edge 단계 통계/degree 통계/config echo가 포함된다.
- [x] 기본값이 normalize 환경의 안전값으로 조정된다(`minSimilarity=0.65`).
- [x] mutual-kNN/SNN/degree-cap 정책이 필터 순서대로 적용된다.
- [x] small merge affinity guardrail과 split retry 옵션이 동작한다.
- [x] 회귀 테스트(5개 시나리오) 포함 `:doc-api:test` 통과.

## Completion Notes
- `NeighborBuilder`에 similarity 분포(reservoir sampling), auto-threshold decision, 단계별 edge pruning 통계를 추가했다.
- `TreeClusterer`에 `clusterWithStats`를 추가하고 merge affinity threshold 및 oversized split retry guardrail을 적용했다.
- `TreeFeedbackAdminServices`의 `tree_rebuild_stage`/`tree_rebuild_summary` payload를 확장해 분포/필터/degree/cluster/config echo를 기록한다.
- 설정 기본값 및 문서(`application.yml`, `.env.example`, `docs/DEV_SETUP.md`, `README.md`)를 운영 권장값으로 동기화했다.
- 테스트: `./gradlew -p services :doc-api:test` 통과.

## Testing
- Unit: NeighborBuilder/TreeClusterer/TreeTelemetry payload assertions
- Regression: over-connect bad/good, mutual on-off, SNN prune, merge affinity
- Full: `./gradlew -p services :doc-api:test`

## Observability
- 로그에는 본문/원문/토큰 원문 비노출
- 숫자 통계(분포, 카운트, 설정값)만 출력

## Rollout / Rollback
- 롤아웃: 설정 기본값 반영 후 품질 지표 모니터링
- 롤백: 새 설정 키를 기존 값으로 되돌리고 정책 플래그 off

## Security / Privacy
- 테넌트 경계 로직 변경 없음
- 로그 민감정보 차단 원칙 유지
