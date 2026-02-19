export type PipelineStatus = {
  ingest: string;
  embed: string;
  index: string;
  tree: string;
  failure_reason?: string | null;
};

export type AttachmentSummary = {
  id: string;
  content_type: string;
  size: number;
};

export type DocumentItem = {
  id: string;
  title: string;
  status: string;
  parent_document_id?: string | null;
  pipeline_status: PipelineStatus;
  attachments: AttachmentSummary[];
  updated_at: string;
  body_markdown?: string;
  workspace_id?: string;
  version?: number;
};
