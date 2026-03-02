CREATE TABLE IF NOT EXISTS document_personal_top (
  workspace_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  ord INT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (workspace_id, user_id, document_id),
  CONSTRAINT fk_document_personal_top_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_document_personal_top_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_document_personal_top_document FOREIGN KEY (document_id) REFERENCES documents(id)
);

CREATE INDEX IF NOT EXISTS idx_document_personal_top_workspace_user_ord
  ON document_personal_top(workspace_id, user_id, ord, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_document_personal_top_workspace_document
  ON document_personal_top(workspace_id, document_id);
