CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(36) PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  token_hash VARCHAR(128) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS workspaces (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  created_by VARCHAR(36) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_workspace_creator FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS memberships (
  workspace_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  role VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (workspace_id, user_id),
  CONSTRAINT fk_membership_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS documents (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  title VARCHAR(255) NOT NULL,
  body_markdown TEXT,
  body_text TEXT,
  source_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_by VARCHAR(36) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_document_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_document_creator FOREIGN KEY (created_by) REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_documents_workspace ON documents(workspace_id);

CREATE TABLE IF NOT EXISTS pipeline_status (
  workspace_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  ingest_status VARCHAR(32) NOT NULL,
  embed_status VARCHAR(32) NOT NULL,
  index_status VARCHAR(32) NOT NULL,
  tree_status VARCHAR(32) NOT NULL,
  failure_reason VARCHAR(512) NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (workspace_id, document_id),
  CONSTRAINT fk_pipeline_document FOREIGN KEY (document_id) REFERENCES documents(id)
);
CREATE INDEX IF NOT EXISTS idx_pipeline_workspace ON pipeline_status(workspace_id);

CREATE TABLE IF NOT EXISTS attachments (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(255) NOT NULL,
  size BIGINT NOT NULL,
  object_key VARCHAR(1024) NOT NULL,
  checksum_sha256 VARCHAR(128) NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP NULL,
  CONSTRAINT fk_attachment_document FOREIGN KEY (document_id) REFERENCES documents(id)
);
CREATE INDEX IF NOT EXISTS idx_attachments_workspace_document ON attachments(workspace_id, document_id);

CREATE TABLE IF NOT EXISTS document_sections (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  ord INT NOT NULL,
  heading VARCHAR(255) NULL,
  chunk_text TEXT NOT NULL,
  quality_flags VARCHAR(255) NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_sections_document FOREIGN KEY (document_id) REFERENCES documents(id)
);
CREATE INDEX IF NOT EXISTS idx_sections_workspace_document_ord ON document_sections(workspace_id, document_id, ord);

CREATE TABLE IF NOT EXISTS embeddings (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id VARCHAR(64) NOT NULL,
  vector_json TEXT NOT NULL,
  model_version VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE(workspace_id, target_type, target_id, model_version),
  CONSTRAINT fk_embeddings_document FOREIGN KEY (document_id) REFERENCES documents(id)
);
CREATE INDEX IF NOT EXISTS idx_embeddings_workspace_document ON embeddings(workspace_id, document_id);

CREATE TABLE IF NOT EXISTS outbox_event (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_json TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  available_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_outbox_workspace_status_available ON outbox_event(workspace_id, status, available_at);

CREATE TABLE IF NOT EXISTS dlq_event (
  id VARCHAR(36) PRIMARY KEY,
  outbox_event_id VARCHAR(36) NOT NULL,
  workspace_id VARCHAR(36) NOT NULL,
  reason VARCHAR(512) NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_dlq_outbox FOREIGN KEY (outbox_event_id) REFERENCES outbox_event(id)
);
CREATE INDEX IF NOT EXISTS idx_dlq_workspace ON dlq_event(workspace_id);

CREATE TABLE IF NOT EXISTS stage_execution (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  stage VARCHAR(32) NOT NULL,
  input_hash VARCHAR(128) NOT NULL,
  model_version VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  message VARCHAR(512) NULL,
  retries INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  UNIQUE(workspace_id, document_id, stage, input_hash, model_version)
);
CREATE INDEX IF NOT EXISTS idx_stage_execution_workspace_document ON stage_execution(workspace_id, document_id);

CREATE TABLE IF NOT EXISTS tree_snapshot (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  status VARCHAR(32) NOT NULL,
  moved_ratio NUMERIC(6,3) NOT NULL DEFAULT 0,
  churn_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  activated_at TIMESTAMP NULL,
  activated_by VARCHAR(36) NULL
);
CREATE INDEX IF NOT EXISTS idx_tree_snapshot_workspace_status ON tree_snapshot(workspace_id, status);

CREATE TABLE IF NOT EXISTS tree_node (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  snapshot_id VARCHAR(36) NOT NULL,
  parent_id VARCHAR(36) NULL,
  label VARCHAR(255) NOT NULL,
  depth INT NOT NULL,
  locked BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_tree_node_snapshot FOREIGN KEY (snapshot_id) REFERENCES tree_snapshot(id)
);
CREATE INDEX IF NOT EXISTS idx_tree_node_workspace_snapshot ON tree_node(workspace_id, snapshot_id);

CREATE TABLE IF NOT EXISTS tree_membership (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  snapshot_id VARCHAR(36) NOT NULL,
  node_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  rationale_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE(workspace_id, snapshot_id, document_id),
  CONSTRAINT fk_tree_membership_snapshot FOREIGN KEY (snapshot_id) REFERENCES tree_snapshot(id),
  CONSTRAINT fk_tree_membership_node FOREIGN KEY (node_id) REFERENCES tree_node(id),
  CONSTRAINT fk_tree_membership_document FOREIGN KEY (document_id) REFERENCES documents(id)
);
CREATE INDEX IF NOT EXISTS idx_tree_membership_workspace_node ON tree_membership(workspace_id, node_id);

CREATE TABLE IF NOT EXISTS feedback_event (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_feedback_workspace_created ON feedback_event(workspace_id, created_at);

CREATE TABLE IF NOT EXISTS audit_log (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  actor_user_id VARCHAR(36) NOT NULL,
  action VARCHAR(64) NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_workspace_created ON audit_log(workspace_id, created_at);
