CREATE TABLE IF NOT EXISTS workspace_question_control (
  workspace_id VARCHAR(36) PRIMARY KEY,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_by VARCHAR(36) NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS active_learning_question (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  snapshot_id VARCHAR(36),
  question_type VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  payload_json TEXT NOT NULL,
  impact_score DOUBLE PRECISION NOT NULL DEFAULT 0,
  answer_value VARCHAR(32),
  answered_by VARCHAR(36),
  answered_at TIMESTAMP,
  expires_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_active_learning_question_workspace_status
  ON active_learning_question(workspace_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_active_learning_question_workspace_document
  ON active_learning_question(workspace_id, document_id, status);
