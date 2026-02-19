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

## POST /documents/{documentId}/pipeline/retry
```json
{ "stage": "EMBED" }
```
- `OWNER|MEMBER` only
- 해당 stage 상태가 `FAILED`일 때만 허용
- outbox `StageRetry` 이벤트를 enqueue
- 요청 즉시 선택 stage부터 downstream stage 상태를 `PENDING`으로 리셋하고 문서 상태를 `PROCESSING`으로 전환
- 워커는 선택한 stage부터 downstream stage까지 연쇄 실행
  - 예: `stage=EMBED` -> `EMBED -> INDEX -> TREE`
  - 동일 input hash의 stage execution이 이미 `DONE`이면 해당 pipeline stage를 `DONE`으로 동기화하여 상태 불일치를 정리

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
Query:
- `q` (required)
- `mode=bm25|hybrid` (default `bm25`)
- `debug=true|false` (default `false`)
- `page`, `size`

Notes:
- workspace scope는 `X-Workspace-Id` + membership 검증으로 강제됩니다.
- lexical(BM25)과 vector(kNN) 모두 동일한 `workspace_id` filter를 적용합니다.
- hybrid는 BM25 + vector 결과를 RRF로 결합하며, vector 경로 실패 시 BM25로 fail-soft 합니다.

Response:
```json
{
  "items": [
    { "document_id": "doc_1", "title": "Locking strategy", "score": 12.3 }
  ],
  "debug": {
    "workspace_id": "ws_1",
    "index_alias": "docs-active",
    "resolved_index_name": ["docs-v2-20260219010101"],
    "workspace_indexed_doc_count": 42,
    "search_backend": "hybrid",
    "lang_detected": "ko",
    "vector_used": true,
    "vector_reason": "ok",
    "bm25_operator": "and",
    "bm25_minimum_should_match": null,
    "bm25_legacy_fallback": false,
    "bm25_recall_fallback_applied": false,
    "top_ranks": [
      {
        "document_id": "doc_1",
        "bm25_rank": 1,
        "knn_rank": 2,
        "rrf_score": 0.0325,
        "score": 0.0325
      }
    ]
  }
}
```

---

# 6) Tree
## GET /tree/active?view=topic|project|timeline|version|template
Response:
```json
{
  "snapshot_id": "ts_1",
  "status": "ACTIVE",
  "view_type": "topic",
  "nodes": [
    {
      "id": "n_1",
      "parent_id": "n_root",
      "label": "OpenSearch",
      "node_type": "topic",
      "locked": false,
      "documents": ["doc_1"],
      "document_summaries": [
        {
          "id": "doc_1",
          "title": "Locking strategy",
          "quarantine_reason": "LOW_CONFIDENCE",
          "placement_confidence": 0.62,
          "template_score": 0.71,
          "template_boilerplate_ratio": 0.66,
          "template_ngram_repeat_ratio": 0.28,
          "template_reasons": ["BOILERPLATE_RATIO", "SCORE_THRESHOLD"],
          "placement_candidates": [
            { "node_id": "n_2", "label": "Distributed Lock", "score": 0.81 },
            { "node_id": "n_5", "label": "Scheduler", "score": 0.74 }
          ]
        }
      ]
    }
  ]
}
```

## GET /tree/snapshots?view=topic|project|timeline|version|template
Response:
```json
{
  "items": [
    {
      "id": "ts_1",
      "view_type": "project",
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
{ "mode": "DEBOUNCED", "view": "topic" }
```

## GET /trees?view=topic|project|timeline|version|template
Response:
```json
{
  "snapshot_id": "ts_2",
  "status": "ACTIVE",
  "view_type": "project",
  "nodes": []
}
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
    "evidence": {
      "neighbors": [
        {
          "document_id": "doc_2",
          "title": "ShedLock notes",
          "channel_scores": { "semantic": 0.91, "lexical": 0.44, "final": 0.84 },
          "edge_decision": {
            "lexical_gate_passed": true,
            "reason_code": "EMBEDDING_LEXICAL_GATED",
            "entity_overlap": 2,
            "title_overlap": 1
          }
        }
      ],
      "reason_codes": ["EMBEDDING_LEXICAL_GATED", "CLUSTER_DEFAULT"]
    },
    "llm_sentence": "핵심 키워드와 상위 이웃 근거를 바탕으로 자동 배치되었습니다."
  }
}
```
주의:
- 본문 원문/추출 텍스트는 포함하지 않음(PII-safe)
- `evidence.neighbors`는 최대 3개, UI 기본 노출은 2~3개

## POST /documents/{documentId}/explain/accept
- 현재 자동 배치를 사용자가 수용(accept)했다는 피드백 이벤트를 기록
- Response: `204 No Content`

---

# 8) Feedback
## POST /feedback/move
```json
{
  "document_id": "doc_1",
  "from_node_id": "n_1",
  "to_node_id": "n_2",
  "source": "DRAG"
}
```

`source`는 선택 사항이며 `DRAG | MANUAL | QUICK_CONFIRM` 중 하나를 권장한다(미지정 시 서버가 `UNKNOWN` 처리).

## POST /feedback/rename
```json
{ "node_id": "n_2", "old_label": "Misc", "new_label": "Reservations" }
```

# 9) Questions
## GET /questions
Query:
- `status`: `OPEN | ANSWERED | EXPIRED` (optional)
- `limit`: default `20`

Response:
```json
{
  "items": [
    {
      "id": "q_1",
      "question_type": "DOC_CLUSTER_CHOICE",
      "status": "OPEN",
      "document_id": "doc_1",
      "impact_score": 0.74,
      "payload": {
        "document_title": "Locking strategy",
        "option_a": { "node_id": "n_2", "label": "billing", "score": 0.81 },
        "option_b": { "node_id": "n_5", "label": "ops", "score": 0.72 }
      }
    }
  ],
  "open_count": 4
}
```

## POST /questions/{questionId}/answer
```json
{ "answer": "A" }
```

`question_type`에 따른 `answer`:
- `DOC_CLUSTER_CHOICE`: `A` 또는 `B`
- `DOC_PAIR_RELATION`: `SAME` 또는 `DIFF`

---

# 10) Admin/Ops
> Owner role required.
## GET /admin/jobs
Query: `document_id` optional

## POST /admin/jobs/retry
```json
{ "document_id": "doc_1", "stage": "EMBED" }
```

## GET /admin/audit
Query:
- `type` (optional)
- `actor_user_id` (optional)
- `q` (optional, action/actor/payload text match)
- `sort` (optional, `desc|asc`, default `desc`)
- `limit` (optional, `1..500`, default `100`)

Response (example):
```json
{
  "items": [
    {
      "id": "audit_1",
      "workspace_id": "ws_1",
      "actor_user_id": "user_1",
      "action": "admin.retry",
      "payload": {
        "document_id": "doc_1",
        "stage": "EMBED"
      },
      "created_at": "2026-02-11T11:20:30"
    }
  ],
  "sort": "desc",
  "limit": 100
}
```

## GET /admin/tree/policy
Response (example):
```json
{
  "workspace_id": "ws_1",
  "auto_threshold": 0.8,
  "recommend_threshold": 0.6,
  "quarantine_enabled": true,
  "reranker_enabled": false,
  "source": "DEFAULT",
  "updated_by": null,
  "updated_at": null
}
```

## PATCH /admin/tree/policy
```json
{
  "auto_threshold": 0.85,
  "recommend_threshold": 0.65,
  "quarantine_enabled": true,
  "reranker_enabled": false
}
```

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
    "neighbor_edge_budget": 6,
    "assign_auto_threshold": 0.8,
    "assign_recommend_threshold": 0.6,
    "assign_quarantine_enabled": true,
    "assign_reranker_enabled": false
  },
  "models": {
    "embedding_provider": "ollama",
    "embedding_model": "bge-m3"
  },
  "decision_summary": {
    "status": "ACTIVE",
    "moved_ratio": 0.12,
    "churn_count": 2,
    "unsorted_ratio": 0.08,
    "auto_ratio": 0.62,
    "recommend_ratio": 0.18,
    "policy_threshold": {
      "auto": 0.8,
      "recommend": 0.6,
      "quarantine_enabled": true,
      "reranker_enabled": false,
      "source": "OVERRIDE"
    }
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
  "auto_ratio": 0.62,
  "recommend_ratio": 0.18,
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
      "rule_effect": "HARD",
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
{
  "rule_type": "TITLE_CONTAINS",
  "rule_value": "invoice",
  "rule_effect": "HARD",
  "node_id": "n_2"
}
```

지원 `rule_type`:
- `TITLE_CONTAINS`
- `ENTITY_CONTAINS`
- `SOURCE_TYPE`
- `AUTHOR`
- `FILENAME_EXT`
- `TAG`

`rule_effect`:
- `HARD` (강제 라우팅)
- `SOFT` (낮은 확신 구간에서 우선 라우팅)

## PATCH /admin/tree/rules/{ruleId}
```json
{
  "rule_type": "SOURCE_TYPE",
  "rule_value": "upload",
  "rule_effect": "SOFT",
  "node_id": "n_2"
}
```

## POST /admin/tree/rules/preview
샘플 문서 기준으로 규칙 매칭 결과와 라우팅 노드를 미리 확인한다.
```json
{
  "document_id": "doc_1",
  "rule_type": "SOURCE_TYPE",
  "rule_value": "editor",
  "rule_effect": "SOFT",
  "node_id": "n_2"
}
```
Response:
```json
{
  "document_id": "doc_1",
  "rule_type": "SOURCE_TYPE",
  "rule_value": "editor",
  "rule_effect": "SOFT",
  "matched": true,
  "target_node_id": "n_2",
  "target_node_label": "billing"
}
```

## DELETE /admin/tree/rules/{ruleId}

## GET /admin/tree/questions/analytics
Response:
```json
{
  "control": {
    "enabled": true,
    "updated_by": "u_1",
    "updated_at": "2026-02-11T10:00:00"
  },
  "open_count": 4,
  "answered_count": 12,
  "expired_count": 1,
  "answer_rate": 0.92,
  "avg_impact_open": 0.63,
  "avg_impact_answered": 0.71,
  "unsorted_ratio": 0.11,
  "items": []
}
```

## PATCH /admin/tree/questions/control
```json
{ "enabled": false }
```

## POST /admin/tree/questions/expire

## POST /admin/tree/questions/generate
