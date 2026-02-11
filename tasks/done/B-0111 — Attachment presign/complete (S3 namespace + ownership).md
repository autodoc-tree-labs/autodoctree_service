# B-0111 — Attachment presign/complete (S3 namespace + ownership)


## Goal
업로드 프리사인 + 완료 처리, workspace 소유권 강제.

## Scope
- POST /attachments/presign
  - storage key: workspaces/{ws}/attachments/{att}/{filename}
  - expiry <= 15m
- POST /attachments/complete
  - ownership verify
  - emit outbox AttachmentUploaded
- checksum optional

## Acceptance Criteria
- cross-tenant presign/complete denied
- presigned URL 저장/로그 금지

