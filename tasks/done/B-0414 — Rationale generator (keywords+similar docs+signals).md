# B-0414 — Rationale generator (keywords+similar docs+signals)


## Goal
문서 배치 근거(rationale)를 생성/저장한다.

## Scope
- rationale_json fields:
  - keywords (top5)
  - similar_docs (top3 ids+score)
  - signals (enum list)
- membership 저장 시 rationale 포함
- fallback: 일부 필드 비어도 스키마 유지

## Acceptance Criteria
- explain에서 rationale 바로 반환 가능
- 민감정보/본문 포함 금지

