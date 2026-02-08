export type PipelineStatus = {
  ingest: string;
  embed: string;
  index: string;
  tree: string;
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
  pipeline_status: PipelineStatus;
  attachments: AttachmentSummary[];
  updated_at: string;
};
