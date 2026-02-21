ALTER TABLE documents
  ADD COLUMN IF NOT EXISTS updated_by VARCHAR(36);

UPDATE documents
SET updated_by = created_by
WHERE updated_by IS NULL;

ALTER TABLE documents
  ALTER COLUMN updated_by SET NOT NULL;



CREATE TABLE IF NOT EXISTS document_acl (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  principal_user_id VARCHAR(36) NOT NULL,
  permission VARCHAR(16) NOT NULL,
  granted_by VARCHAR(36) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE(workspace_id, document_id, principal_user_id),
  CONSTRAINT fk_document_acl_document FOREIGN KEY (document_id) REFERENCES documents(id),
  CONSTRAINT fk_document_acl_principal FOREIGN KEY (principal_user_id) REFERENCES users(id),
  CONSTRAINT fk_document_acl_granted_by FOREIGN KEY (granted_by) REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_document_acl_workspace_user ON document_acl(workspace_id, principal_user_id);

CREATE TABLE IF NOT EXISTS workspace_invites (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  email VARCHAR(255) NOT NULL,
  role VARCHAR(32) NOT NULL,
  token_hash VARCHAR(128) NOT NULL UNIQUE,
  invited_by VARCHAR(36) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  accepted_at TIMESTAMP NULL,
  accepted_by VARCHAR(36) NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_workspace_invite_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_workspace_invite_inviter FOREIGN KEY (invited_by) REFERENCES users(id),
  CONSTRAINT fk_workspace_invite_accepted_by FOREIGN KEY (accepted_by) REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_workspace_invites_workspace_email ON workspace_invites(workspace_id, email);

CREATE TABLE IF NOT EXISTS palette_history (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  query_text VARCHAR(256) NULL,
  document_id VARCHAR(36) NULL,
  command_key VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_palette_history_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_palette_history_document FOREIGN KEY (document_id) REFERENCES documents(id)
);
CREATE INDEX IF NOT EXISTS idx_palette_history_workspace_user_created ON palette_history(workspace_id, user_id, created_at DESC);
