ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS parent_document_id VARCHAR(36) NULL;

CREATE INDEX IF NOT EXISTS idx_documents_workspace_parent
    ON documents(workspace_id, parent_document_id);
