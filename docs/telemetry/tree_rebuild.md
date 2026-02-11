# Tree Rebuild Telemetry v1

## Goals
- Rebuild path를 단계별(`ingest`, `embed`, `pairwise`, `graph`, `cluster`, `assign`, `tree_extract`)로 분해해 원인 분석 가능하게 유지한다.
- 문서 원문/본문/첨부 내용은 로그에 남기지 않는다.

## Log Events

### `tree_rebuild_stage`
- emitted: stage 1회 완료 시 1건
- payload keys
  - `event`: `tree_rebuild_stage`
  - `workspace_id`
  - `stage`
  - `duration_ms`
  - `trace_id`
  - `request_id`
  - `details` (count/ratio 중심, PII 제외)

example:
```text
tree_rebuild_stage {event=tree_rebuild_stage, workspace_id=ws_1, stage=graph, duration_ms=12.144, ...}
```

### `tree_rebuild_summary`
- emitted: rebuild 1회 완료 시 1건
- payload keys
  - `event`: `tree_rebuild_summary`
  - `workspace_id`
  - `snapshot_id`
  - `status`
  - `document_count`
  - `edge_count`
  - `filtered_edge_count`
  - `avg_similarity`
  - `mutual_pass_rate`
  - `snn_pass_rate`
  - `hub_doc_count`
  - `moved_ratio`
  - `churn_ratio`
  - `unsorted_ratio`
  - `unsorted_reason_breakdown`
  - `stage_count`
  - `stage_durations_ms`
  - `embedding_provider`, `embedding_model`
  - `llm_provider`, `llm_model`
  - `embedding_available_doc_ratio`
  - `similarity_source_breakdown`
  - `label_source_breakdown`
  - `trace_id`, `request_id`

## Metrics
- `tree.rebuild.duration.ms`
- `tree.rebuild.stage.duration.ms`
- `tree.rebuild.similarity.distribution`
- `tree.rebuild.degree.distribution`
- `tree.rebuild.cluster_size.distribution`
- `tree.rebuild.unsorted_ratio`
- `tree.neighbor_builder.mutual_pass_rate`
- `tree.neighbor_builder.snn_pass_rate`
- `tree.neighbor_builder.hub_doc_count`
- `tree.neighbor_builder.lexical_token_count`
- `tree.neighbor_builder.tfidf_compute`
- `tree.neighbor_builder.lexical_gate_pass_rate`
- `tree.assign_policy_total{decision=AUTO|RECOMMEND|UNSORTED}`
- `auto_ratio`
- `recommend_ratio`
- `explain_shown_total`
- `explain_accept_total`
- `feedback_move_total`
- `feedback_move_source_total{source=DRAG|MANUAL|QUICK_CONFIRM|UNKNOWN}`

기존 호환 메트릭도 유지:
- `tree_rebuild_duration_ms`
- `moved_ratio`
- `churn_ratio`
- `neighbor_edges_total`
- `edges_filtered_total`

## Debug API Masking Rules
- title/body 원문 미노출
- 문서 제목은 `title_mask = { hash, length }` 형태로만 전달
- neighbors/cluster/rebuild 디버그 응답은 id/점수/사유 코드 중심
