ALTER TABLE tree_snapshot
    ADD COLUMN IF NOT EXISTS view_type VARCHAR(32) NOT NULL DEFAULT 'TOPIC';

ALTER TABLE tree_node
    ADD COLUMN IF NOT EXISTS view_type VARCHAR(32) NOT NULL DEFAULT 'TOPIC';

ALTER TABLE tree_membership
    ADD COLUMN IF NOT EXISTS view_type VARCHAR(32) NOT NULL DEFAULT 'TOPIC';

CREATE INDEX IF NOT EXISTS idx_tree_snapshot_workspace_view_status
  ON tree_snapshot(workspace_id, view_type, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tree_node_workspace_snapshot_view
  ON tree_node(workspace_id, snapshot_id, view_type);

CREATE INDEX IF NOT EXISTS idx_tree_membership_workspace_snapshot_view
  ON tree_membership(workspace_id, snapshot_id, view_type);
