# B-0510 — Multi-tenant E2E security test suite (wsA/wsB) — GA Gate


## Goal
테넌트 누수 회귀를 CI에서 차단.

## Scope
- 자동 테스트:
  - wsA/wsB 생성
  - wsA 문서/첨부/스냅샷 생성
  - wsB로 wsA 리소스 접근 시도:
    - doc read/list/search/tree/explain/presign/complete/admin → deny/0
- Testcontainers 권장(DB/OS/Redis/MinIO)

## Acceptance Criteria
- CI에서 항상 실행
- 실패 시 어떤 엔드포인트가 누수인지 메시지로 식별 가능

