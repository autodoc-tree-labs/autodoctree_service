# API_SURFACE.md — REST API v1 (Draft)
Base URL: `/api/v1`

## Auth & tenant scoping
- 인증: `Authorization: Bearer <access_token>`
- 테넌트 선택: `X-Workspace-Id: <workspace_id>` (tenant-scoped endpoints 필수)
- 서버는 항상 `(user_id, workspace_id)` membership을 검증하고 role을 결정한다.
- 바디/쿼리의 workspace_id는 **참고용**이며, 서버 컨텍스트와 불일치 시 거절한다.

## Common headers
- `X-Request-Id` (optional; server generates if missing)

## Error schema
```json
{
  "error": {
    "code": "TENANT_FORBIDDEN",
    "message": "Access denied",
    "trace_id": "01J....",
    "details": { "hint": "workspace scope mismatch" }
  }
}
```

## Health & metrics
- `GET /health` → `{ "status": "OK" }`
- `GET /metrics` → `{ "meters": ["..."] }`

---

# 1) Auth
## POST /auth/login
```json
{ "email": "user@example.com", "password": "..." }
```

## POST /auth/refresh
```json
{ "refresh_token": "..." }
```

## POST /auth/logout
```json
{ "refresh_token": "..." }
```

---

# 2) Workspaces
## GET /workspaces
Response:
```json
{ "items": [ { "id": "ws_1", "name": "Personal", "role": "OWNER" } ] }
```

## POST /workspaces
```json
{ "name": "Team Alpha" }
```
Response:
```json
{ "id": "ws_1", "name": "Team Alpha" }
```

## GET /workspaces/{workspaceId}/members
## POST /workspaces/{workspaceId}/members
```json
{ "email": "new@ex.com", "role": "MEMBER" }
```
## PATCH /workspaces/{workspaceId}/members/{userId}
```json
{ "role": "VIEWER" }
```
## DELETE /workspaces/{workspaceId}/members/{userId}

---

# 3) Documents
## POST /documents
```json
{
  "title": "Locking strategy",
  "body_markdown": "# ...",
  "source_type": "EDITOR"
}
```

## GET /documents/{documentId}
Response (example):
```json
{
  "id": "doc_1",
  "workspace_id": "ws_1",
  "title": "Locking strategy",
  "status": "PROCESSING",
  "pipeline_status": { "ingest": "DONE", "embed": "RUNNING", "index": "PENDING", "tree": "PENDING" },
  "attachments": [ { "id": "att_1", "content_type": "application/pdf", "size": 12345 } ]
}
```

## GET /documents
Query: `status`, `q`, `page`, `size`, `sort`

## PATCH /documents/{documentId}
- title/body updates (optimistic locking recommended)
```json
{
  "version": 0,
  "title": "new title",
  "body_markdown": "# updated"
}
```

## DELETE /documents/{documentId}
- soft delete

---

# 4) Attachments (S3/MinIO)
## POST /attachments/presign
```json
{
  "document_id": "doc_1",
  "filename": "spec.pdf",
  "content_type": "application/pdf",
  "size": 1048576,
  "checksum_sha256": "..."
}
```
Response:
```json
{ "attachment_id": "att_1", "upload_url": "https://...presigned...", "expires_in_seconds": 900 }
```

## POST /attachments/complete
```json
{ "attachment_id": "att_1" }
```

---

# 5) Search
## GET /search
Query: `q` (required), `mode=bm25|vector|hybrid`, `page`, `size`, `sort`

Response:
```json
{
  "items": [
    { "document_id": "doc_1", "title": "Locking strategy", "score": 12.3 }
  ]
}
```

---

# 6) Tree
## GET /tree/active
Response:
```json
{
  "snapshot_id": "ts_1",
  "status": "ACTIVE",
  "nodes": [
    { "id": "n_root", "parent_id": null, "label": "BSL", "locked": false },
    { "id": "n_1", "parent_id": "n_root", "label": "OpenSearch", "locked": false }
  ]
}
```

## GET /tree/snapshots
## POST /tree/rebuild
```json
{ "mode": "DEBOUNCED" }
```

## POST /tree/snapshots/{snapshotId}/activate
```json
{ }
```

## POST /tree/nodes/{nodeId}/lock
```json
{ "locked": true }
```

---

# 7) Explain
## GET /documents/{documentId}/explain
Response:
```json
{
  "document_id": "doc_1",
  "node_id": "n_1",
  "rationale": {
    "keywords": ["lock", "concurrency", "transaction"],
    "similar_docs": [
      { "document_id": "doc_2", "title": "ShedLock notes", "similarity": 0.91 }
    ],
    "signals": ["HYBRID_SIM_HIGH"]
  }
}
```

---

# 8) Feedback
## POST /feedback/move
```json
{ "document_id": "doc_1", "from_node_id": "n_1", "to_node_id": "n_2" }
```

## POST /feedback/rename
```json
{ "node_id": "n_2", "old_label": "Misc", "new_label": "Reservations" }
```

---

# 9) Admin/Ops
> Owner role required.
## GET /admin/jobs
Query: `document_id` optional

## POST /admin/jobs/retry
```json
{ "document_id": "doc_1", "stage": "EMBED" }
```

## GET /admin/audit
Query: `type`, `from`, `to`
