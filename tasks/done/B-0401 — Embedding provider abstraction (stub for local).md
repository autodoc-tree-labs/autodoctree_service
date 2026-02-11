# B-0401 — Embedding provider abstraction (stub for local)


## Goal
외부 API/로컬 스텁 임베딩 제공자 추상화.

## Scope
- interface: embed(List<Text>) -> List<Vector>
- local stub provider deterministic vectors
- timeout/retry/rate limit hooks

## Acceptance Criteria
- config로 provider 선택 가능
- 텍스트 로그 금지

