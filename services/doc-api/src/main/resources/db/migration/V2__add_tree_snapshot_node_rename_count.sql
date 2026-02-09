ALTER TABLE tree_snapshot
    ADD COLUMN IF NOT EXISTS node_rename_count INT NOT NULL DEFAULT 0;
