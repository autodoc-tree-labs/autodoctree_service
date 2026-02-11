ALTER TABLE tree_snapshot
ADD COLUMN IF NOT EXISTS label_cache_json TEXT NOT NULL DEFAULT '{}';
