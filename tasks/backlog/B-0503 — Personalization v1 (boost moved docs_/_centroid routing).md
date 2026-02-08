# B-0503 — Personalization v1 (boost moved docs / centroid routing)


## Goal
피드백으로 다음 자동 분류 품질을 개선(최소 구현).

## Scope
- move 이벤트를 라벨로 사용:
  - 특정 키워드/엔티티/임베딩 centroid에 가중치
- rebuild 시:
  - 유사 문서는 사용자가 자주 넣는 노드로 우선 배치(soft preference)

## Acceptance Criteria
- 사용자가 몇 번 이동하면 다음 문서가 더 잘 배치되는 “체감”이 발생
- 안전장치: 과적합 방지(감쇠)

