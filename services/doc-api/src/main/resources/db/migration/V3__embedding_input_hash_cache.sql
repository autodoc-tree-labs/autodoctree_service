ALTER TABLE embeddings
ADD COLUMN IF NOT EXISTS input_hash VARCHAR(128);

UPDATE embeddings
SET input_hash = COALESCE(input_hash, 'legacy');

ALTER TABLE embeddings
ALTER COLUMN input_hash SET NOT NULL;

ALTER TABLE embeddings
DROP CONSTRAINT IF EXISTS embeddings_workspace_id_target_type_target_id_model_version_key;

ALTER TABLE embeddings
ADD CONSTRAINT uq_embeddings_target_model_input
UNIQUE(workspace_id, target_type, target_id, model_version, input_hash);

CREATE INDEX IF NOT EXISTS idx_embeddings_workspace_target_model_hash
ON embeddings(workspace_id, target_type, target_id, model_version, input_hash);
