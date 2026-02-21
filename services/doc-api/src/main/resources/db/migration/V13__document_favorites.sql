CREATE TABLE IF NOT EXISTS document_favorite (
  workspace_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (workspace_id, user_id, document_id),
  CONSTRAINT fk_document_favorite_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_document_favorite_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_document_favorite_document FOREIGN KEY (document_id) REFERENCES documents(id)
);

CREATE INDEX IF NOT EXISTS idx_document_favorite_workspace_user_created
  ON document_favorite(workspace_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_document_favorite_workspace_document
  ON document_favorite(workspace_id, document_id);
