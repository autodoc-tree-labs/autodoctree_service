# RUNBOOK

## OpenSearch Reindex (B-0303)

### Current strategy
- Index template: `search.template-name` (default `docs-template`)
- Read/write alias: `search.index-alias` (default `docs-active`)
- Versioned index pattern: `${alias-prefix}-v1-*` (default `docs-v1-*`)
- Initial write index: `${alias-prefix}-v1-000001` (default `docs-v1-000001`)

### Verify alias + template
```bash
curl -s http://localhost:59200/_index_template/docs-template | jq
curl -s http://localhost:59200/_alias/docs-active | jq
```

### Reindex to a new versioned index
1. Create destination index (example `docs-v1-000002`)
```bash
curl -s -X PUT http://localhost:59200/docs-v1-000002 -H 'Content-Type: application/json' -d '{}'
```

2. Reindex from current alias
```bash
curl -s -X POST http://localhost:59200/_reindex -H 'Content-Type: application/json' -d '{
  \"source\": { \"index\": \"docs-active\" },
  \"dest\": { \"index\": \"docs-v1-000002\" }
}'
```

3. Switch write alias to the new index
```bash
curl -s -X POST http://localhost:59200/_aliases -H 'Content-Type: application/json' -d '{
  \"actions\": [
    { \"add\": { \"index\": \"docs-v1-000001\", \"alias\": \"docs-active\", \"is_write_index\": false } },
    { \"add\": { \"index\": \"docs-v1-000002\", \"alias\": \"docs-active\", \"is_write_index\": true } }
  ]
}'
```

4. Validate search and index updates
```bash
curl -s \"http://localhost:59200/docs-active/_search?q=workspace_id:YOUR_WORKSPACE_ID\" | jq '.hits.total'
```

### Rollback
- Re-run alias switch and set previous index to `is_write_index: true`.
- Do not delete the previous index until read/search behavior is confirmed.

## Tree Quality Metrics (I-0614)

### Key metrics
- `tree_rebuild_duration_ms`
- `neighbor_edges_total`
- `edges_filtered_total`
- `cluster_count`
- `avg_cluster_size`
- `label_filtered_total{reason=...}`
- `avg_label_length`
- `moved_ratio`
- `churn_ratio`
- `feedback_applied_total`
- `rules_applied_total`

### Dashboard template
- Grafana JSON draft: `docs/grafana/tree_quality_dashboard.json`
- Import the JSON in Grafana dashboard UI and point panels to the Prometheus datasource used by `doc-api`.

### Quick check
```bash
curl -s http://localhost:8080/actuator/prometheus | rg 'tree_rebuild_duration_ms|neighbor_edges_total|cluster_count|label_filtered_total'
```

## Tree Quality Regression Golden Set (B-0607)

### Test fixture
- Golden set file: `services/doc-api/src/test/resources/tree/golden_set_v1.json`
- Current composition: 50 docs / 5 topics (finance, sports, science, literature, travel)

### Run regression suite
```bash
./gradlew -p services :doc-api:test --tests com.autodoctree.api.domain.TreeQualityRegressionTest
```

### Fixture update process
1. Edit `services/doc-api/src/test/resources/tree/golden_set_v1.json` with explicit topic tags.
2. Run the regression test and inspect failure diff output (purity/churn/label metrics).
3. If intentional algorithm updates changed expected behavior, commit fixture update in the same PR with rationale.
