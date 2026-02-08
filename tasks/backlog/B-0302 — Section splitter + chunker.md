# B-0302 — Section splitter + chunker


## Goal
섹션/청크 분할로 embedding과 explain에 사용.

## Scope
- heading-based split when possible
- fallback length-based chunking + overlap
- store document_sections

## Acceptance Criteria
- 순서 보존(ord)
- chunk size 제한 준수

