CREATE TABLE IF NOT EXISTS concept_prototype (
  id VARCHAR(36) PRIMARY KEY,
  workspace_id VARCHAR(36) NOT NULL,
  snapshot_id VARCHAR(36) NOT NULL,
  concept_key VARCHAR(160) NOT NULL,
  label VARCHAR(255) NOT NULL,
  prototype_vector_json TEXT NOT NULL,
  exemplar_doc_ids_json TEXT NOT NULL,
  doc_count INT NOT NULL DEFAULT 0,
  drift_score DOUBLE PRECISION NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  UNIQUE(workspace_id, snapshot_id, concept_key),
  CONSTRAINT fk_concept_prototype_snapshot FOREIGN KEY (snapshot_id) REFERENCES tree_snapshot(id)
);

CREATE INDEX IF NOT EXISTS idx_concept_prototype_workspace_snapshot
  ON concept_prototype(workspace_id, snapshot_id);

CREATE INDEX IF NOT EXISTS idx_concept_prototype_workspace_label
  ON concept_prototype(workspace_id, label);
