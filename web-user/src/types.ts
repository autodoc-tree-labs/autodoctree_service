export type PipelineStatus = {
  ingest: string;
  embed: string;
  index: string;
  tree: string;
  failure_reason?: string | null;
};

export type AttachmentSummary = {
  id: string;
  filename?: string;
  content_type: string;
  size: number;
  status?: string;
  download_url?: string | null;
};

export type DocumentItem = {
  id: string;
  title: string;
  status: string;
  parent_document_id?: string | null;
  pipeline_status: PipelineStatus;
  attachments: AttachmentSummary[];
  updated_at: string;
  created_at?: string;
  created_by?: string;
  updated_by?: string;
  body_markdown?: string;
  blocks_json?: unknown;
  workspace_id?: string;
  version?: number;
};
