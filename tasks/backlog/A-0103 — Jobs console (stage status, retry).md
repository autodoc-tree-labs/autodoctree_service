# A-0103 — Jobs console (stage status, retry)


## Goal
문서별 파이프라인 상태와 재처리 트리거 UI.

## Scope
- GET /admin/jobs
- document_id 검색
- retry 버튼 → POST /admin/jobs/retry
- 결과 토스트/로그

## Acceptance Criteria
- 재처리 성공/실패가 명확히 표시

