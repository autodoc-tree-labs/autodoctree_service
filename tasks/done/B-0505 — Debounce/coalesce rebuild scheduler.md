# B-0505 — Debounce/coalesce rebuild scheduler


## Goal
rebuild 요청/이벤트를 workspace 단위로 합쳐 비용과 요동을 줄인다.

## Scope
- debounce window (e.g., 30s)
- coalesce multiple triggers into one rebuild
- manual rebuild endpoint는 즉시 또는 우선순위 높게

## Acceptance Criteria
- 짧은 시간에 N번 저장해도 rebuild 1번 수행

