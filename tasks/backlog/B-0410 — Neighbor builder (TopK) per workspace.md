# B-0410 — Neighbor builder (TopK) per workspace


## Goal
트리 생성을 위한 문서 유사도 TopK 이웃 그래프 구축.

## Scope
- input: workspace docs with embeddings (or hybrid signals)
- output: adjacency list per doc (TopK)
- tenant-filtered searches only

## Acceptance Criteria
- K configurable
- 품질/성능 기본지표 기록(시간/문서수)

