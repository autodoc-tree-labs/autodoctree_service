CREATE TABLE IF NOT EXISTS workspace_tree_policy (
  workspace_id VARCHAR(36) PRIMARY KEY,
  auto_threshold NUMERIC(6,3) NOT NULL DEFAULT 0.80,
  recommend_threshold NUMERIC(6,3) NOT NULL DEFAULT 0.60,
  quarantine_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  reranker_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  updated_by VARCHAR(36) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_workspace_tree_policy_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
);
