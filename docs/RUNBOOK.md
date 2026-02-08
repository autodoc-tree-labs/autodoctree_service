# RUNBOOK

## OpenSearch Reindex (B-0303)

### Current strategy
- Index template: `search.template-name` (default `docs-template`)
- Read/write alias: `search.index-alias` (default `docs-active`)
- Versioned index pattern: `${alias-prefix}-v1-*` (default `docs-v1-*`)
- Initial write index: `${alias-prefix}-v1-000001` (default `docs-v1-000001`)

### Verify alias + template
```bash
curl -s http://localhost:9200/_index_template/docs-template | jq
curl -s http://localhost:9200/_alias/docs-active | jq
```

### Reindex to a new versioned index
1. Create destination index (example `docs-v1-000002`)
```bash
curl -s -X PUT http://localhost:9200/docs-v1-000002 -H 'Content-Type: application/json' -d '{}'
```

2. Reindex from current alias
```bash
curl -s -X POST http://localhost:9200/_reindex -H 'Content-Type: application/json' -d '{
  \"source\": { \"index\": \"docs-active\" },
  \"dest\": { \"index\": \"docs-v1-000002\" }
}'
```

3. Switch write alias to the new index
```bash
curl -s -X POST http://localhost:9200/_aliases -H 'Content-Type: application/json' -d '{
  \"actions\": [
    { \"add\": { \"index\": \"docs-v1-000001\", \"alias\": \"docs-active\", \"is_write_index\": false } },
    { \"add\": { \"index\": \"docs-v1-000002\", \"alias\": \"docs-active\", \"is_write_index\": true } }
  ]
}'
```

4. Validate search and index updates
```bash
curl -s \"http://localhost:9200/docs-active/_search?q=workspace_id:YOUR_WORKSPACE_ID\" | jq '.hits.total'
```

### Rollback
- Re-run alias switch and set previous index to `is_write_index: true`.
- Do not delete the previous index until read/search behavior is confirmed.
