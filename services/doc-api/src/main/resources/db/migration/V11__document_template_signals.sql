ALTER TABLE documents
  ADD COLUMN IF NOT EXISTS template_score NUMERIC(6,3);

ALTER TABLE documents
  ADD COLUMN IF NOT EXISTS template_boilerplate_ratio NUMERIC(6,3);

ALTER TABLE documents
  ADD COLUMN IF NOT EXISTS template_ngram_repeat_ratio NUMERIC(6,3);

ALTER TABLE documents
  ADD COLUMN IF NOT EXISTS template_detected_at TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_documents_workspace_template_detected
  ON documents(workspace_id, template_detected_at);
