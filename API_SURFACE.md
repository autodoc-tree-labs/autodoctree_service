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
Response:
```json
{
  "items": [
    {
      "id": "ts_1",
      "status": "ACTIVE",
      "moved_ratio": 0.12,
      "churn_count": 3,
      "node_rename_count": 2,
      "created_at": "2024-01-01T10:00:00Z"
    }
  ]
}
```
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
    "signals": ["HYBRID_SIM_HIGH"],
    "llm_sentence": "동시성 키워드와 높은 유사도 신호로 이 노드에 배치되었습니다."
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

## GET /admin/tree/debug/neighbors
Query: `document_id` (required)

Response:
```json
{
  "document_id": "doc_1",
  "title": "Locking strategy",
  "neighbors": [
    {
      "neighbor_doc_id": "doc_2",
      "title": "Concurrency notes",
      "sem_sim": 0.91,
      "lex_sim": 0.54,
      "entity_overlap": 2,
      "final_sim": 0.84,
      "gate_flags": {
        "lexical_gate_passed": true,
        "reason": "EMBEDDING_LEXICAL_GATED"
      }
    }
  ]
}
```

## GET /admin/tree/debug/docs/{documentId}
Query: `top_n` (optional, default `8`)

Response (example):
```json
{
  "document_id": "doc_1",
  "title_mask": { "hash": "sha256:...", "length": 32 },
  "assignment": {
    "node_id": "node_3",
    "node_label": "billing",
    "snapshot_id": "ts_2",
    "quarantine_reason": null
  },
  "assignment_confidence": 0.41,
  "neighbors": [
    {
      "neighbor_doc_id": "doc_2",
      "title_mask": { "hash": "sha256:...", "length": 21 },
      "channel_scores": {
        "semantic": 0.91,
        "lexical": 0.54,
        "final": 0.84
      },
      "edge_decision": {
        "lexical_gate_passed": true,
        "reason": "EMBEDDING_LEXICAL_GATED",
        "entity_overlap": 2,
        "title_overlap": 1
      }
    }
  ],
  "trace_id": "..."
}
```

## GET /admin/tree/debug/clusters/{clusterId}
Response (example):
```json
{
  "cluster_id": "node_3",
  "snapshot_id": "ts_2",
  "label": "billing",
  "member_count": 4,
  "members": [
    {
      "document_id": "doc_1",
      "title_mask": { "hash": "sha256:...", "length": 32 },
      "signals": ["CLUSTER_DEFAULT"]
    }
  ],
  "exemplars": [
    {
      "document_id": "doc_2",
      "title_mask": { "hash": "sha256:...", "length": 21 },
      "avg_similarity": 0.83
    }
  ],
  "label_candidates": ["invoice", "billing"],
  "trace_id": "..."
}
```

## GET /admin/tree/debug/rebuilds/{snapshotId}
Response (example):
```json
{
  "snapshot_id": "ts_2",
  "status": "ACTIVE",
  "parameters": {
    "neighbor_top_k": 5,
    "neighbor_min_similarity": 0.25,
    "neighbor_mutual_knn_required": true,
    "neighbor_snn_threshold": 0.12,
    "neighbor_edge_budget": 6
  },
  "models": {
    "embedding_provider": "ollama",
    "embedding_model": "bge-m3"
  },
  "decision_summary": {
    "status": "ACTIVE",
    "moved_ratio": 0.12,
    "churn_count": 2,
    "unsorted_ratio": 0.08
  },
  "stage_logs": [
    { "stage": "graph", "duration_ms": 11.2, "details": { "edge_count": 42 } }
  ],
  "trace_id": "..."
}
```

## GET /admin/tree/debug/cluster-stats
Response (example):
```json
{
  "snapshot_id": "ts_2",
  "status": "ACTIVE",
  "cluster_count": 8,
  "avg_cluster_size": 4.2,
  "neighbor_edges_total": 312,
  "edges_filtered_total": 98,
  "mutual_pass_rate": 0.84,
  "snn_pass_rate": 0.78,
  "hub_doc_count": 3,
  "label_filtered_total": 3,
  "avg_label_length": 6.4,
  "tree_rebuild_duration_ms": 182.5,
  "moved_ratio": 0.13,
  "churn_ratio": 0.13
}
```

## GET /admin/tree/rules
Response:
```json
{
  "items": [
    {
      "id": "rule_1",
      "rule_type": "TITLE_CONTAINS",
      "rule_value": "invoice",
      "node_id": "n_2",
      "node_label": "billing",
      "enabled": true,
      "created_at": "2026-02-10T09:00:00"
    }
  ]
}
```

## POST /admin/tree/rules
```json
{ "rule_type": "TITLE_CONTAINS", "rule_value": "invoice", "node_id": "n_2" }
```

## DELETE /admin/tree/rules/{ruleId}
