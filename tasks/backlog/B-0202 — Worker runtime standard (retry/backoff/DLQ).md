# B-0202 — Worker runtime standard (retry/backoff/DLQ)


## Goal
워커 표준: 재시도/백오프/DLQ/리스(중복 실행 제어)

## Scope
- common worker loop framework
- retry policy + DLQ table
- structured logs: stage, workspace_id, document_id

## Acceptance Criteria
- poison event는 max retry 후 DLQ
- 운영자가 재처리 가능(후속 admin ticket 연동)

