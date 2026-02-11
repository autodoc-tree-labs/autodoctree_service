CREATE TABLE IF NOT EXISTS user_rule (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  rule_type VARCHAR(32) NOT NULL,
  rule_value VARCHAR(255) NOT NULL,
  node_id VARCHAR(36) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by VARCHAR(36) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE(workspace_id, rule_type, rule_value, node_id),
  CONSTRAINT fk_user_rule_node FOREIGN KEY (node_id) REFERENCES tree_node(id)
);

CREATE INDEX IF NOT EXISTS idx_user_rule_workspace ON user_rule(workspace_id, enabled, created_at);
