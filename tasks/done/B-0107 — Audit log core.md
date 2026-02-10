# B-0107 — Audit log core


## Goal
민감 작업을 감사로그로 남겨 운영/감사 대응.

## Scope
- audit_log table + writer
- events:
  - membership 변경
  - snapshot activate
  - node lock/unlock
  - hard delete/purge (있다면)
  - presign issued (metadata only)

## Acceptance Criteria
- 각 이벤트 발생 시 audit row 기록
- /admin/audit 조회 가능(tenant scope 적용)
- payload에 본문/추출텍스트/URL 금지

