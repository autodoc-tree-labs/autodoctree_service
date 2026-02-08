# B-0411 — Clustering v1 (2-depth)


## Goal
이웃 그래프를 기반으로 2-depth 클러스터(폴더) 생성.

## Scope
- 1st-level clustering (e.g., HAC/Community detection)
- 2nd-level split for large clusters
- enforce max depth=2, bounded cluster sizes

## Acceptance Criteria
- 최소 N docs에서 트리 생성 가능
- 트리 구조가 너무 잦게 변하지 않도록 안정화 훅 제공(후속 B-0504)

