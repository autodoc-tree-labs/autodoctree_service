import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate, useParams } from "react-router-dom";
import { ApiError, type Workspace } from "@autodoctree/api-client";
import { CircleHelp, FileText, GitBranch, Plus, Search, Trash2, type LucideIcon } from "lucide-react";
import { createApiClient } from "./api";
import { useSession } from "./session";
import type { DocumentItem } from "./types";

type AuthResponse = {
  access_token: string;
  refresh_token: string;
};

type WorkspaceListResponse = { items: Workspace[] };
type DocumentListResponse = { items: DocumentItem[]; page: number; size: number; total: number };
type FavoriteDocumentListResponse = { items: Array<{ document_id: string; created_at?: string }>; total: number };
type SearchResponse = { items: Array<{ document_id: string; title: string; score: number; breadcrumb?: string[] }>; debug?: Record<string, unknown> };
type PaletteHistoryResponse = { items: Array<{ id: string; event_type: string; query_text?: string; document_id?: string; command_key?: string; created_at: string }> };
type TreeNodeDocumentSummary = {
  id: string;
  title: string;
  quarantine_reason?: string | null;
  placement_confidence?: number | null;
  placement_candidates?: Array<{
    node_id: string;
    label: string;
    score: number;
  }>;
};

type TreeNode = {
  id: string;
  parent_id: string | null;
  label: string;
  locked: boolean;
  documents: string[];
  document_summaries?: TreeNodeDocumentSummary[];
};
type TreeActiveResponse = {
  snapshot_id: string | null;
  status: string;
  view_type?: string;
  nodes: TreeNode[];
};

type TreeView = "topic" | "project" | "timeline" | "version" | "template";

type TreeDocumentView = {
  id: string;
  title: string;
  node_id: string;
  node_label: string;
  quarantine_reason: string | null;
  placement_confidence: number | null;
  placement_candidates: Array<{
    node_id: string;
    label: string;
    score: number;
  }>;
};

type EditorSidebarWorkspaceState = {
  parents: Record<string, string | null>;
  favorites: string[];
};

type EditorTreeNode = {
  doc: DocumentItem;
  children: EditorTreeNode[];
};

type EditorSidebarMode = "DOCUMENT" | "NODE";

type EditorNodeTree = {
  id: string;
  label: string;
  locked: boolean;
  documents: Array<{ id: string; title: string }>;
  children: EditorNodeTree[];
};

type SidebarPageNode = {
  doc: DocumentItem;
  children: SidebarPageNode[];
};

type SidebarViewKey = "documents" | "tree" | "questions" | "trash" | "workspace";
type AppApiClient = ReturnType<typeof createApiClient>;

type QuestionItem = {
  id: string;
  question_type: string;
  status: string;
  document_id: string;
  impact_score: number;
  payload: Record<string, unknown>;
  created_at?: string;
  expires_at?: string | null;
};

type QuestionListResponse = {
  items: QuestionItem[];
  open_count: number;
};

type ExplainResponse = {
  document_id: string;
  node_id: string | null;
  rationale?: {
    keywords?: string[];
    similar_docs?: Array<{ document_id: string; title: string; similarity: number }>;
    signals?: string[];
    evidence?: {
      neighbors?: Array<{
        document_id: string;
        title: string;
        channel_scores?: {
          semantic?: number | null;
          lexical?: number | null;
          final?: number | null;
        };
        edge_decision?: {
          lexical_gate_passed?: boolean;
          reason_code?: string;
          entity_overlap?: number | null;
          title_overlap?: number | null;
        };
      }>;
      reason_codes?: string[];
    };
    llm_sentence?: string | null;
  };
};

type UiError = {
  message: string;
  status: number | null;
};

type UiNoticeTone = "info" | "success";

type UiNotice = {
  message: string;
  tone: UiNoticeTone;
};

type StatusTone = "neutral" | "good" | "warn" | "bad";
type PipelineStage = "INGEST" | "EMBED" | "INDEX" | "TREE";

const STATUS_TEXT: Record<string, string> = {
  DONE: "완료",
  SUCCESS: "성공",
  RUNNING: "진행 중",
  PROCESSING: "처리 중",
  PENDING: "대기",
  FAILED: "실패",
  ERROR: "오류"
};

const ROLE_TEXT: Record<string, string> = {
  OWNER: "소유자",
  MEMBER: "멤버",
  VIEWER: "조회자"
};

const TREE_INBOX_NODE_ID = "__virtual_inbox__";
const TREE_TEMPLATES_NODE_ID = "__virtual_templates__";
const TREE_VIEW_PREFERENCE_KEY = "autodoc.tree.view.by_workspace.v1";
const EDITOR_SIDEBAR_STATE_KEY = "autodoc.editor.sidebar.by_workspace.v1";
const TREE_VIEW_OPTIONS: Array<{ value: TreeView; label: string }> = [
  { value: "topic", label: "Topic" },
  { value: "project", label: "Project" },
  { value: "timeline", label: "Timeline" },
  { value: "version", label: "Version" },
  { value: "template", label: "Template" }
];
const REBUILD_REQUEST_TIMEOUT_MS = 15_000;
const LAST_WORKSPACE_ID_KEY = "autodoc.user.last-workspace.v1";
const SIDEBAR_WIDTH_PREFERENCE_KEY = "autodoc.workspace.sidebar-width.by-workspace.v1";
const SIDEBAR_WIDTH_DEFAULT = 272;
const SIDEBAR_WIDTH_MIN = 220;
const SIDEBAR_WIDTH_MAX = 520;
const SIDEBAR_WIDTH_STEP = 12;
const COMMAND_PALETTE_MAX_RESULTS = 8;

const REASON_TEXT: Record<string, string> = {
  LOW_CONFIDENCE: "신뢰도 낮음",
  HUB: "허브 문서",
  CONFLICT: "혼합 신호",
  TEMPLATE: "템플릿 의심",
  RECOMMEND: "추천 검토"
};

const ERROR_MESSAGE_TEXT: Array<[string, string]> = [
  ["X-Workspace-Id header is required", "워크스페이스 헤더(X-Workspace-Id)가 필요합니다."],
  ["Failed to fetch", "서버 연결에 실패했습니다. 백엔드 실행과 CORS 설정을 확인하세요."],
  ["NetworkError when attempting to fetch resource", "서버 연결에 실패했습니다. 네트워크 상태를 확인하세요."],
  ["Load failed", "서버 연결에 실패했습니다."],
  ["Network request failed", "네트워크 요청에 실패했습니다."],
  ["Unauthorized", "인증이 필요합니다."],
  ["Forbidden", "권한이 없습니다."],
  ["Not Found", "요청한 리소스를 찾을 수 없습니다."]
];

const hasHangul = (value: string): boolean => /[가-힣]/.test(value);

const localizeErrorMessage = (message: string, fallback: string): string => {
  const trimmed = message.trim();
  if (!trimmed) {
    return fallback;
  }

  for (const [needle, translated] of ERROR_MESSAGE_TEXT) {
    if (trimmed.includes(needle)) {
      return translated;
    }
  }

  if (hasHangul(trimmed)) {
    return trimmed;
  }

  return fallback;
};

const toUiError = (error: unknown, fallback: string): UiError => {
  if (error instanceof ApiError) {
    return {
      message: localizeErrorMessage(error.message, fallback),
      status: error.status
    };
  }
  if (error instanceof Error) {
    return {
      message: localizeErrorMessage(error.message, fallback),
      status: null
    };
  }
  return {
    message: fallback,
    status: null
  };
};

class RequestTimeoutError extends Error {
  constructor(message = "request_timeout") {
    super(message);
    this.name = "RequestTimeoutError";
  }
}

const withRequestTimeout = <T,>(promise: Promise<T>, timeoutMs: number): Promise<T> =>
  new Promise<T>((resolve, reject) => {
    const timeoutId = window.setTimeout(() => {
      reject(new RequestTimeoutError());
    }, timeoutMs);

    promise
      .then((value) => {
        window.clearTimeout(timeoutId);
        resolve(value);
      })
      .catch((error) => {
        window.clearTimeout(timeoutId);
        reject(error);
      });
  });

const statusTone = (value: string): StatusTone => {
  const normalized = value.trim().toUpperCase();
  if (normalized === "DONE" || normalized === "SUCCESS") {
    return "good";
  }
  if (normalized === "RUNNING" || normalized === "PROCESSING" || normalized === "PENDING") {
    return "warn";
  }
  if (normalized === "FAILED" || normalized === "ERROR") {
    return "bad";
  }
  return "neutral";
};

const statusText = (value: string): string => STATUS_TEXT[value.trim().toUpperCase()] ?? value;

const roleText = (value: string): string => ROLE_TEXT[value.trim().toUpperCase()] ?? value;

const workspaceRootPath = (workspaceId: string): string => `/w/${encodeURIComponent(workspaceId)}`;

const workspaceViewPath = (workspaceId: string, view: Exclude<SidebarViewKey, "workspace">): string =>
  `/w/${encodeURIComponent(workspaceId)}/view/${view}`;

const workspaceDocumentPath = (workspaceId: string, documentId: string): string =>
  `/w/${encodeURIComponent(workspaceId)}/doc/${encodeURIComponent(documentId)}`;

const workspaceDocumentDetailPath = (workspaceId: string, documentId: string): string =>
  `/w/${encodeURIComponent(workspaceId)}/doc/${encodeURIComponent(documentId)}/details`;

const detectSidebarView = (pathname: string): SidebarViewKey => {
  if (pathname.includes("/view/tree") || pathname.endsWith("/tree")) {
    return "tree";
  }
  if (pathname.includes("/view/questions") || pathname.endsWith("/questions")) {
    return "questions";
  }
  if (pathname.includes("/view/trash") || pathname.endsWith("/trash")) {
    return "trash";
  }
  if (pathname === "/workspace" || pathname.startsWith("/workspace/")) {
    return "workspace";
  }
  return "documents";
};

const parseTimestamp = (value: string | undefined): number => {
  if (!value) {
    return 0;
  }
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : 0;
};

const sortDocumentsByRecency = (left: DocumentItem, right: DocumentItem): number => {
  const delta = parseTimestamp(right.updated_at) - parseTimestamp(left.updated_at);
  if (delta !== 0) {
    return delta;
  }
  return left.title.localeCompare(right.title, "ko");
};

const buildSidebarPageTree = (documents: DocumentItem[]): SidebarPageNode[] => {
  const ordered = [...documents].sort(sortDocumentsByRecency);
  const nodeById = new Map<string, SidebarPageNode>();
  ordered.forEach((document) => {
    nodeById.set(document.id, { doc: document, children: [] });
  });

  const roots: SidebarPageNode[] = [];
  ordered.forEach((document) => {
    const node = nodeById.get(document.id);
    if (!node) {
      return;
    }
    const parentId = document.parent_document_id ?? null;
    const parent = parentId ? nodeById.get(parentId) : undefined;
    if (parent && parentId !== document.id) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  });

  const sortNodes = (nodes: SidebarPageNode[]) => {
    nodes.sort((left, right) => sortDocumentsByRecency(left.doc, right.doc));
    nodes.forEach((node) => {
      if (node.children.length > 0) {
        sortNodes(node.children);
      }
    });
  };
  sortNodes(roots);
  return roots;
};

const filterSidebarPageTree = (nodes: SidebarPageNode[], query: string): SidebarPageNode[] => {
  const normalized = query.trim().toLowerCase();
  if (!normalized) {
    return nodes;
  }
  const walk = (node: SidebarPageNode): SidebarPageNode | null => {
    const matched = node.doc.title.toLowerCase().includes(normalized);
    const children = node.children.map(walk).filter((child): child is SidebarPageNode => child !== null);
    if (!matched && children.length === 0) {
      return null;
    }
    return { doc: node.doc, children };
  };
  return nodes.map(walk).filter((node): node is SidebarPageNode => node !== null);
};

const loadLastWorkspaceId = (): string | null => {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    const saved = window.localStorage.getItem(LAST_WORKSPACE_ID_KEY);
    return saved?.trim() ? saved : null;
  } catch {
    return null;
  }
};

const saveLastWorkspaceId = (workspaceId: string | null) => {
  if (!workspaceId || typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(LAST_WORKSPACE_ID_KEY, workspaceId);
  } catch {
    // Ignore localStorage write failures in local/dev environments.
  }
};

const clampSidebarWidth = (value: number): number =>
  Math.min(SIDEBAR_WIDTH_MAX, Math.max(SIDEBAR_WIDTH_MIN, Math.round(value)));

const loadSidebarWidthPreference = (workspaceId: string | null): number => {
  if (!workspaceId || typeof window === "undefined") {
    return SIDEBAR_WIDTH_DEFAULT;
  }
  try {
    const raw = window.localStorage.getItem(SIDEBAR_WIDTH_PREFERENCE_KEY);
    if (!raw) {
      return SIDEBAR_WIDTH_DEFAULT;
    }
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const saved = parsed[workspaceId];
    if (typeof saved !== "number" || !Number.isFinite(saved)) {
      return SIDEBAR_WIDTH_DEFAULT;
    }
    return clampSidebarWidth(saved);
  } catch {
    return SIDEBAR_WIDTH_DEFAULT;
  }
};

const saveSidebarWidthPreference = (workspaceId: string | null, width: number) => {
  if (!workspaceId || typeof window === "undefined") {
    return;
  }
  try {
    const raw = window.localStorage.getItem(SIDEBAR_WIDTH_PREFERENCE_KEY);
    const parsed = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
    parsed[workspaceId] = clampSidebarWidth(width);
    window.localStorage.setItem(SIDEBAR_WIDTH_PREFERENCE_KEY, JSON.stringify(parsed));
  } catch {
    // Ignore localStorage write failures in local/dev environments.
  }
};

const PIPELINE_STAGE_LABEL: Record<PipelineStage, string> = {
  INGEST: "수집",
  EMBED: "임베딩",
  INDEX: "인덱스",
  TREE: "트리"
};

const getFailedPipelineStage = (doc: DocumentItem): PipelineStage | null => {
  const stageStatuses: Array<{ stage: PipelineStage; status: string }> = [
    { stage: "INGEST", status: doc.pipeline_status.ingest },
    { stage: "EMBED", status: doc.pipeline_status.embed },
    { stage: "INDEX", status: doc.pipeline_status.index },
    { stage: "TREE", status: doc.pipeline_status.tree }
  ];
  const failed = stageStatuses.find((entry) => {
    const normalized = entry.status.trim().toUpperCase();
    return normalized === "FAILED" || normalized === "ERROR";
  });
  return failed?.stage ?? null;
};

const isUnsortedNodeLabel = (label: string): boolean => {
  const normalized = label.trim().toLowerCase();
  return normalized === "general" || normalized === "unsorted";
};

const reasonText = (value: string | null): string | null => {
  if (!value) {
    return null;
  }
  const normalized = value.trim().toUpperCase();
  return REASON_TEXT[normalized] ?? normalized;
};

const isTreeView = (value: unknown): value is TreeView =>
  typeof value === "string" && TREE_VIEW_OPTIONS.some((option) => option.value === value);

const loadTreeViewPreference = (workspaceId: string | null): TreeView => {
  if (!workspaceId || typeof window === "undefined") {
    return "topic";
  }
  try {
    const raw = window.localStorage.getItem(TREE_VIEW_PREFERENCE_KEY);
    if (!raw) {
      return "topic";
    }
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const saved = parsed[workspaceId];
    return isTreeView(saved) ? saved : "topic";
  } catch {
    return "topic";
  }
};

const saveTreeViewPreference = (workspaceId: string | null, view: TreeView) => {
  if (!workspaceId || typeof window === "undefined") {
    return;
  }
  try {
    const raw = window.localStorage.getItem(TREE_VIEW_PREFERENCE_KEY);
    const parsed = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
    parsed[workspaceId] = view;
    window.localStorage.setItem(TREE_VIEW_PREFERENCE_KEY, JSON.stringify(parsed));
  } catch {
    // Ignore preference write failures in local environments.
  }
};

const toTreeDocumentView = (node: TreeNode, summary: TreeNodeDocumentSummary): TreeDocumentView => ({
  id: summary.id,
  title: summary.title || summary.id,
  node_id: node.id,
  node_label: node.label,
  quarantine_reason: summary.quarantine_reason ?? null,
  placement_confidence:
    typeof summary.placement_confidence === "number" && !Number.isNaN(summary.placement_confidence)
      ? Math.max(0, Math.min(1, summary.placement_confidence))
      : null,
  placement_candidates: (summary.placement_candidates ?? [])
    .filter((candidate) => candidate.node_id && candidate.label)
    .map((candidate) => ({
      node_id: candidate.node_id,
      label: candidate.label,
      score: Number.isFinite(candidate.score) ? Math.max(0, Math.min(1, candidate.score)) : 0
    }))
});

const emptyEditorSidebarState = (): EditorSidebarWorkspaceState => ({
  parents: {},
  favorites: []
});

const normalizeEditorSidebarState = (state: EditorSidebarWorkspaceState): EditorSidebarWorkspaceState => {
  const parents: Record<string, string | null> = {};
  for (const [docId, parentId] of Object.entries(state.parents)) {
    if (!docId) {
      continue;
    }
    if (typeof parentId === "string" && parentId.trim()) {
      parents[docId] = parentId;
    }
  }
  const favorites = Array.from(new Set(state.favorites.filter((value) => value.trim().length > 0)));
  return { parents, favorites };
};

const parseEditorSidebarWorkspaceState = (raw: unknown): EditorSidebarWorkspaceState => {
  if (!raw || typeof raw !== "object") {
    return emptyEditorSidebarState();
  }
  const record = raw as Record<string, unknown>;
  const parentsRaw = record.parents;
  const favoritesRaw = record.favorites;

  const parents: Record<string, string | null> = {};
  if (parentsRaw && typeof parentsRaw === "object") {
    for (const [docId, parentId] of Object.entries(parentsRaw as Record<string, unknown>)) {
      if (typeof docId !== "string" || !docId.trim()) {
        continue;
      }
      if (typeof parentId === "string" && parentId.trim()) {
        parents[docId] = parentId;
      }
    }
  }

  const favorites = Array.isArray(favoritesRaw)
    ? favoritesRaw.filter((value): value is string => typeof value === "string" && value.trim().length > 0)
    : [];

  return normalizeEditorSidebarState({ parents, favorites });
};

const loadEditorSidebarState = (workspaceId: string | null): EditorSidebarWorkspaceState => {
  if (!workspaceId || typeof window === "undefined") {
    return emptyEditorSidebarState();
  }
  try {
    const raw = window.localStorage.getItem(EDITOR_SIDEBAR_STATE_KEY);
    if (!raw) {
      return emptyEditorSidebarState();
    }
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    return parseEditorSidebarWorkspaceState(parsed[workspaceId]);
  } catch {
    return emptyEditorSidebarState();
  }
};

const saveEditorSidebarState = (workspaceId: string | null, state: EditorSidebarWorkspaceState) => {
  if (!workspaceId || typeof window === "undefined") {
    return;
  }
  try {
    const raw = window.localStorage.getItem(EDITOR_SIDEBAR_STATE_KEY);
    const parsed = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
    parsed[workspaceId] = normalizeEditorSidebarState(state);
    window.localStorage.setItem(EDITOR_SIDEBAR_STATE_KEY, JSON.stringify(parsed));
  } catch {
    // Ignore localStorage errors in local environments.
  }
};

const sanitizeEditorSidebarState = (state: EditorSidebarWorkspaceState, documents: DocumentItem[]): EditorSidebarWorkspaceState => {
  const docIds = new Set(documents.map((document) => document.id));
  const parents: Record<string, string | null> = {};

  for (const document of documents) {
    const parentId = state.parents[document.id];
    if (typeof parentId === "string" && docIds.has(parentId) && parentId !== document.id) {
      parents[document.id] = parentId;
    }
  }

  for (const docId of Object.keys(parents)) {
    const seen = new Set<string>([docId]);
    let cursor = parents[docId];
    while (cursor) {
      if (seen.has(cursor)) {
        delete parents[docId];
        break;
      }
      seen.add(cursor);
      cursor = parents[cursor] ?? null;
    }
  }

  const favorites = state.favorites.filter((docId) => docIds.has(docId));
  return normalizeEditorSidebarState({ parents, favorites });
};

const isEditorSidebarStateEqual = (left: EditorSidebarWorkspaceState, right: EditorSidebarWorkspaceState): boolean => {
  const leftParents = Object.entries(left.parents)
    .filter(([, parentId]) => typeof parentId === "string" && parentId.length > 0)
    .sort(([a], [b]) => a.localeCompare(b));
  const rightParents = Object.entries(right.parents)
    .filter(([, parentId]) => typeof parentId === "string" && parentId.length > 0)
    .sort(([a], [b]) => a.localeCompare(b));

  if (leftParents.length !== rightParents.length) {
    return false;
  }
  for (let index = 0; index < leftParents.length; index += 1) {
    const [leftDocId, leftParentId] = leftParents[index];
    const [rightDocId, rightParentId] = rightParents[index];
    if (leftDocId !== rightDocId || leftParentId !== rightParentId) {
      return false;
    }
  }

  const leftFavorites = Array.from(new Set(left.favorites)).sort((a, b) => a.localeCompare(b));
  const rightFavorites = Array.from(new Set(right.favorites)).sort((a, b) => a.localeCompare(b));
  if (leftFavorites.length !== rightFavorites.length) {
    return false;
  }
  for (let index = 0; index < leftFavorites.length; index += 1) {
    if (leftFavorites[index] !== rightFavorites[index]) {
      return false;
    }
  }
  return true;
};

const copyTextToClipboard = async (text: string): Promise<void> => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  const succeeded = document.execCommand("copy");
  document.body.removeChild(textarea);
  if (!succeeded) {
    throw new Error("clipboard_copy_failed");
  }
};

function StatusChip({ label, value }: { label: string; value: string }) {
  return <span className={`status-chip status-chip-${statusTone(value)}`}>{label}: {statusText(value)}</span>;
}

function PageHeader({ title, subtitle, action }: { title: string; subtitle: string; action?: React.ReactNode }) {
  return (
    <div className="page-header">
      <div>
        <h1 className="page-title">{title}</h1>
        <p className="page-subtitle">{subtitle}</p>
      </div>
      {action ? <div className="page-header-action">{action}</div> : null}
    </div>
  );
}

function EmptyState({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
  return (
    <div className="empty-state">
      <strong>{title}</strong>
      <p>{description}</p>
      {action}
    </div>
  );
}

function WorkspaceRequiredHint() {
  return (
    <EmptyState
      title="워크스페이스를 먼저 선택하세요"
      description="테넌트 범위 페이지는 활성 워크스페이스를 선택해야 접근할 수 있습니다."
      action={<NavLink className="btn btn-primary" to="/workspace">워크스페이스로 이동</NavLink>}
    />
  );
}

function ErrorPanel({ error, onRetry }: { error: UiError | null; onRetry?: () => void }) {
  if (!error) {
    return null;
  }

  const canRetry = Boolean(onRetry) && Boolean(error.status && error.status >= 500);

  return (
    <div className="error-panel" role="alert">
      <div>
        <strong>{error.message}</strong>
        {error.status ? <span className="error-code">상태코드 {error.status}</span> : null}
      </div>
      {canRetry ? (
        <button
          className="btn btn-ghost btn-small"
          onClick={() => {
            onRetry?.();
          }}
          type="button"
        >
          재시도
        </button>
      ) : null}
    </div>
  );
}

function NoticePanel({ notice }: { notice: UiNotice | null }) {
  if (!notice) {
    return null;
  }

  return (
    <div className={`notice-panel notice-panel-${notice.tone}`} role="status" aria-live="polite">
      <strong>{notice.message}</strong>
    </div>
  );
}

type SidebarMenuIconName = "plus" | "search" | "document" | "tree" | "question" | "trash";

const SIDEBAR_MENU_ICONS: Record<SidebarMenuIconName, LucideIcon> = {
  plus: Plus,
  search: Search,
  document: FileText,
  tree: GitBranch,
  question: CircleHelp,
  trash: Trash2
};

function SidebarMenuIcon({ name, className }: { name: SidebarMenuIconName; className?: string }) {
  const Icon = SIDEBAR_MENU_ICONS[name];

  return (
    <span aria-hidden className={className ?? "sidebar-menu-item-icon"}>
      <Icon className="sidebar-menu-item-icon-svg" strokeWidth={1.7} />
    </span>
  );
}

const moveDocumentInTree = (tree: TreeActiveResponse, documentId: string, fromNodeId: string | null, toNodeId: string): TreeActiveResponse => {
  const movedSummary =
    tree.nodes
      .flatMap((node) => node.document_summaries ?? [])
      .find((summary) => summary.id === documentId) ?? null;

  return {
    ...tree,
    nodes: tree.nodes.map((node) => {
      const removeFromNode = fromNodeId ? node.id === fromNodeId : true;
      const withoutDoc = removeFromNode ? node.documents.filter((docId) => docId !== documentId) : node.documents;
      const withoutSummaries = removeFromNode
        ? node.document_summaries?.filter((summary) => summary.id !== documentId)
        : node.document_summaries;
      if (node.id === toNodeId && !withoutDoc.includes(documentId)) {
        const nextSummaries = withoutSummaries ? [...withoutSummaries] : withoutSummaries;
        if (nextSummaries && movedSummary && !nextSummaries.some((summary) => summary.id === documentId)) {
          nextSummaries.push(movedSummary);
        }
        return { ...node, documents: [...withoutDoc, documentId], document_summaries: nextSummaries };
      }
      return { ...node, documents: withoutDoc, document_summaries: withoutSummaries };
    })
  };
};

const renameNodeInTree = (tree: TreeActiveResponse, nodeId: string, label: string): TreeActiveResponse => {
  return {
    ...tree,
    nodes: tree.nodes.map((node) => (node.id === nodeId ? { ...node, label } : node))
  };
};

function Layout({ children, api }: { children: React.ReactNode; api: AppApiClient }) {
  const { state, clearTokens, setWorkspace } = useSession();
  const navigate = useNavigate();
  const location = useLocation();

  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [workspaceError, setWorkspaceError] = useState<UiError | null>(null);
  const [sidebarDocuments, setSidebarDocuments] = useState<DocumentItem[]>([]);
  const [sidebarFavoriteDocumentIds, setSidebarFavoriteDocumentIds] = useState<string[]>([]);
  const [documentError, setDocumentError] = useState<UiError | null>(null);
  const [sidebarFavoriteError, setSidebarFavoriteError] = useState<UiError | null>(null);
  const [sidebarNodeError, setSidebarNodeError] = useState<UiError | null>(null);
  const [sidebarNodeTreeResponse, setSidebarNodeTreeResponse] = useState<TreeActiveResponse | null>(null);
  const [loadingSidebarNodeTree, setLoadingSidebarNodeTree] = useState(false);
  const [pagesBrowseMode, setPagesBrowseMode] = useState<EditorSidebarMode>("DOCUMENT");
  const [sidebarQuery, setSidebarQuery] = useState("");
  const [sidebarPageMenuDocId, setSidebarPageMenuDocId] = useState<string | null>(null);
  const [sidebarRenamingDocumentId, setSidebarRenamingDocumentId] = useState<string | null>(null);
  const [sidebarRenameDraft, setSidebarRenameDraft] = useState("");
  const [sidebarRenamePendingDocumentId, setSidebarRenamePendingDocumentId] = useState<string | null>(null);
  const [sidebarCreatingParentId, setSidebarCreatingParentId] = useState<string | null>(null);
  const [sidebarDeletingDocumentId, setSidebarDeletingDocumentId] = useState<string | null>(null);
  const [sidebarFavoritePendingId, setSidebarFavoritePendingId] = useState<string | null>(null);
  const [sidebarDraggingDocumentId, setSidebarDraggingDocumentId] = useState<string | null>(null);
  const [sidebarDropTarget, setSidebarDropTarget] = useState<{ targetDocumentId: string | null; mode: "CHILD" | "SIBLING" | "ROOT" } | null>(null);
  const [sidebarMovePendingDocumentId, setSidebarMovePendingDocumentId] = useState<string | null>(null);
  const [sidebarActionError, setSidebarActionError] = useState<UiError | null>(null);
  const [sidebarActionNotice, setSidebarActionNotice] = useState<UiNotice | null>(null);
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [sidebarWidth, setSidebarWidth] = useState<number>(SIDEBAR_WIDTH_DEFAULT);
  const [isSidebarResizing, setIsSidebarResizing] = useState(false);
  const [isPaletteOpen, setIsPaletteOpen] = useState(false);
  const [paletteQuery, setPaletteQuery] = useState("");
  const [creatingRootPage, setCreatingRootPage] = useState(false);
  const paletteInputRef = useRef<HTMLInputElement | null>(null);

  const routeWorkspaceMatch = useMemo(() => location.pathname.match(/^\/w\/([^/]+)/), [location.pathname]);
  const routeWorkspaceId = routeWorkspaceMatch?.[1] ? decodeURIComponent(routeWorkspaceMatch[1]) : null;
  const activeWorkspaceId = routeWorkspaceId ?? state.workspaceId;
  const activeView = detectSidebarView(location.pathname);
  const applySidebarWidth = useCallback((nextWidth: number) => {
    setSidebarWidth(clampSidebarWidth(nextWidth));
  }, []);

  useEffect(() => {
    setSidebarWidth(loadSidebarWidthPreference(activeWorkspaceId));
  }, [activeWorkspaceId]);

  useEffect(() => {
    saveSidebarWidthPreference(activeWorkspaceId, sidebarWidth);
  }, [activeWorkspaceId, sidebarWidth]);

  useEffect(() => {
    setIsSidebarOpen(false);
    setSidebarPageMenuDocId(null);
  }, [location.pathname]);

  useEffect(() => {
    if (!state.workspaceId) {
      return;
    }
    saveLastWorkspaceId(state.workspaceId);
  }, [state.workspaceId]);

  useEffect(() => {
    setSidebarActionError(null);
    setSidebarActionNotice(null);
    setSidebarPageMenuDocId(null);
    setSidebarRenamingDocumentId(null);
    setSidebarRenameDraft("");
    setSidebarRenamePendingDocumentId(null);
    setSidebarCreatingParentId(null);
    setSidebarDeletingDocumentId(null);
    setSidebarFavoritePendingId(null);
    setSidebarDraggingDocumentId(null);
    setSidebarDropTarget(null);
    setSidebarMovePendingDocumentId(null);
    setSidebarFavoriteDocumentIds([]);
    setSidebarFavoriteError(null);
  }, [activeWorkspaceId]);

  useEffect(() => {
    if (!state.accessToken) {
      setWorkspaces([]);
      setSidebarDocuments([]);
      return;
    }
    let active = true;
    void (async () => {
      try {
        const response = await api.request<WorkspaceListResponse>("/workspaces");
        if (!active) {
          return;
        }
        setWorkspaces(response.items);
        setWorkspaceError(null);
      } catch (error) {
        if (!active) {
          return;
        }
        setWorkspaceError(toUiError(error, "워크스페이스 목록을 불러오지 못했습니다"));
      }
    })();
    return () => {
      active = false;
    };
  }, [api, state.accessToken]);

  useEffect(() => {
    if (!routeWorkspaceId) {
      return;
    }
    if (state.workspaceId === routeWorkspaceId) {
      return;
    }
    const matched = workspaces.find((workspace) => workspace.id === routeWorkspaceId);
    if (matched) {
      setWorkspace(matched.id, matched.name);
      return;
    }
    setWorkspace(routeWorkspaceId, state.workspaceName ?? routeWorkspaceId);
  }, [routeWorkspaceId, setWorkspace, state.workspaceId, state.workspaceName, workspaces]);

  useEffect(() => {
    if (!state.accessToken || state.workspaceId || routeWorkspaceId || workspaces.length === 0) {
      return;
    }
    const savedWorkspaceId = loadLastWorkspaceId();
    const nextWorkspace = workspaces.find((workspace) => workspace.id === savedWorkspaceId) ?? workspaces[0];
    setWorkspace(nextWorkspace.id, nextWorkspace.name);
    if (!location.pathname.startsWith("/workspace")) {
      navigate(workspaceRootPath(nextWorkspace.id), { replace: true });
    }
  }, [location.pathname, navigate, routeWorkspaceId, setWorkspace, state.accessToken, state.workspaceId, workspaces]);

  const loadSidebarDocuments = useCallback(async () => {
    if (!activeWorkspaceId) {
      setSidebarDocuments([]);
      setSidebarFavoriteDocumentIds([]);
      setSidebarNodeTreeResponse(null);
      setSidebarNodeError(null);
      return;
    }
    try {
      const response = await api.request<DocumentListResponse>("/documents?page=0&size=200", {}, true);
      setSidebarDocuments(response.items);
      setDocumentError(null);
    } catch (error) {
      setDocumentError(toUiError(error, "페이지 목록을 불러오지 못했습니다"));
    }
  }, [activeWorkspaceId, api]);

  const loadSidebarFavorites = useCallback(async () => {
    if (!activeWorkspaceId) {
      setSidebarFavoriteDocumentIds([]);
      setSidebarFavoriteError(null);
      return;
    }
    try {
      const response = await api.request<FavoriteDocumentListResponse>("/documents/favorites", {}, true);
      const favoriteIds = Array.isArray(response.items)
        ? response.items
            .map((item) => item.document_id)
            .filter((value): value is string => typeof value === "string" && value.trim().length > 0)
        : [];
      setSidebarFavoriteDocumentIds(Array.from(new Set(favoriteIds)));
      setSidebarFavoriteError(null);
    } catch (error) {
      setSidebarFavoriteError(toUiError(error, "즐겨찾기 목록을 불러오지 못했습니다"));
    }
  }, [activeWorkspaceId, api]);

  useEffect(() => {
    if (!activeWorkspaceId) {
      setSidebarDocuments([]);
      setSidebarFavoriteDocumentIds([]);
      setSidebarNodeTreeResponse(null);
      setSidebarNodeError(null);
      return;
    }
    void loadSidebarDocuments();
    void loadSidebarFavorites();
  }, [activeWorkspaceId, loadSidebarDocuments, loadSidebarFavorites]);

  const loadSidebarNodeTree = useCallback(async () => {
    if (!activeWorkspaceId) {
      setSidebarNodeTreeResponse(null);
      setSidebarNodeError(null);
      return;
    }
    setLoadingSidebarNodeTree(true);
    setSidebarNodeError(null);
    try {
      const response = await api.request<TreeActiveResponse>("/trees?view=topic", {}, true);
      setSidebarNodeTreeResponse(response);
    } catch (error) {
      setSidebarNodeError(toUiError(error, "노드 분류 목록을 불러오지 못했습니다"));
    } finally {
      setLoadingSidebarNodeTree(false);
    }
  }, [activeWorkspaceId, api]);

  useEffect(() => {
    if (pagesBrowseMode !== "NODE") {
      return;
    }
    void loadSidebarNodeTree();
  }, [loadSidebarNodeTree, pagesBrowseMode]);

  useEffect(() => {
    const openPalette = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setIsPaletteOpen(true);
        return;
      }
      if (event.key === "Escape") {
        setIsPaletteOpen(false);
      }
    };
    window.addEventListener("keydown", openPalette);
    return () => {
      window.removeEventListener("keydown", openPalette);
    };
  }, []);

  useEffect(() => {
    if (!isPaletteOpen) {
      setPaletteQuery("");
      return;
    }
    const timer = window.setTimeout(() => {
      paletteInputRef.current?.focus();
    }, 0);
    return () => {
      window.clearTimeout(timer);
    };
  }, [isPaletteOpen]);

  useEffect(() => {
    if (!sidebarPageMenuDocId) {
      return;
    }

    const closeOnOutsideClick = (event: MouseEvent) => {
      let cursor = event.target as HTMLElement | null;
      while (cursor) {
        if (cursor.dataset.sidebarMenuRoot === sidebarPageMenuDocId) {
          return;
        }
        cursor = cursor.parentElement;
      }
      setSidebarPageMenuDocId(null);
    };

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setSidebarPageMenuDocId(null);
      }
    };

    window.addEventListener("mousedown", closeOnOutsideClick);
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.removeEventListener("mousedown", closeOnOutsideClick);
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [sidebarPageMenuDocId]);

  useEffect(() => {
    if (sidebarRenamingDocumentId && sidebarRenamingDocumentId !== sidebarPageMenuDocId) {
      setSidebarRenamingDocumentId(null);
      setSidebarRenameDraft("");
      setSidebarRenamePendingDocumentId(null);
    }
  }, [sidebarPageMenuDocId, sidebarRenamingDocumentId]);

  const pageTreeRoots = useMemo(() => buildSidebarPageTree(sidebarDocuments), [sidebarDocuments]);
  const filteredPageTreeRoots = useMemo(() => filterSidebarPageTree(pageTreeRoots, sidebarQuery), [pageTreeRoots, sidebarQuery]);
  const sidebarFavoriteSet = useMemo(() => new Set(sidebarFavoriteDocumentIds), [sidebarFavoriteDocumentIds]);
  const sidebarDocumentById = useMemo(() => new Map(sidebarDocuments.map((document) => [document.id, document])), [sidebarDocuments]);
  const favoriteSidebarDocuments = useMemo(() => {
    const matched = sidebarFavoriteDocumentIds
      .map((documentId) => sidebarDocumentById.get(documentId))
      .filter((document): document is DocumentItem => document !== undefined);
    const normalizedQuery = sidebarQuery.trim().toLowerCase();
    if (!normalizedQuery) {
      return matched;
    }
    return matched.filter((document) => document.title.toLowerCase().includes(normalizedQuery));
  }, [sidebarDocumentById, sidebarFavoriteDocumentIds, sidebarQuery]);

  const sidebarNodeTrees = useMemo<EditorNodeTree[]>(() => {
    if (!sidebarNodeTreeResponse) {
      return [];
    }

    const documentById = new Map(sidebarDocuments.map((document) => [document.id, document]));
    const grouped = new Map<
      string,
      {
        id: string;
        parentId: string | null;
        label: string;
        locked: boolean;
        documents: Array<{ id: string; title: string }>;
        children: EditorNodeTree[];
      }
    >();

    for (const node of sidebarNodeTreeResponse.nodes) {
      const summaryTitleById = new Map((node.document_summaries ?? []).map((summary) => [summary.id, summary.title?.trim() ?? ""]));
      const nodeDocumentIds = Array.from(new Set([...(node.documents ?? []), ...(node.document_summaries ?? []).map((summary) => summary.id)]));
      grouped.set(node.id, {
        id: node.id,
        parentId: node.parent_id,
        label: node.label,
        locked: node.locked,
        documents: nodeDocumentIds
          .map((documentId) => ({
            id: documentId,
            title: summaryTitleById.get(documentId) || documentById.get(documentId)?.title || documentId
          }))
          .sort((left, right) => left.title.localeCompare(right.title, "ko")),
        children: []
      });
    }

    const roots: EditorNodeTree[] = [];
    for (const value of grouped.values()) {
      const node: EditorNodeTree = {
        id: value.id,
        label: value.label,
        locked: value.locked,
        documents: value.documents,
        children: value.children
      };
      const parent = value.parentId ? grouped.get(value.parentId) : undefined;
      if (parent) {
        parent.children.push(node);
      } else {
        roots.push(node);
      }
    }

    const sortNodes = (list: EditorNodeTree[]) => {
      list.sort((left, right) => left.label.localeCompare(right.label, "ko"));
      for (const item of list) {
        if (item.children.length > 0) {
          sortNodes(item.children);
        }
      }
    };
    sortNodes(roots);
    return roots;
  }, [sidebarDocuments, sidebarNodeTreeResponse]);

  const filteredSidebarNodeTrees = useMemo(() => {
    const normalized = sidebarQuery.trim().toLowerCase();
    if (!normalized) {
      return sidebarNodeTrees;
    }
    const filterNode = (node: EditorNodeTree): EditorNodeTree | null => {
      const filteredChildren = node.children
        .map((child) => filterNode(child))
        .filter((child): child is EditorNodeTree => child !== null);
      const filteredDocuments = node.documents.filter((document) => document.title.toLowerCase().includes(normalized));
      const matchedLabel = node.label.toLowerCase().includes(normalized);
      if (!matchedLabel && filteredChildren.length === 0 && filteredDocuments.length === 0) {
        return null;
      }
      return {
        ...node,
        children: filteredChildren,
        documents: matchedLabel ? node.documents : filteredDocuments
      };
    };
    return sidebarNodeTrees.map((node) => filterNode(node)).filter((node): node is EditorNodeTree => node !== null);
  }, [sidebarNodeTrees, sidebarQuery]);

  const createRootPage = useCallback(async () => {
    if (!activeWorkspaceId || creatingRootPage) {
      return;
    }
    setCreatingRootPage(true);
    try {
      const created = await api.request<{ id: string }>(
        "/documents",
        {
          method: "POST",
          body: JSON.stringify({
            title: "새 페이지",
            body_markdown: "",
            source_type: "EDITOR",
            parent_document_id: null
          })
        },
        true
      );
      navigate(workspaceDocumentPath(activeWorkspaceId, created.id));
    } catch {
      // Keep action non-blocking inside quick action.
    } finally {
      setCreatingRootPage(false);
      setIsPaletteOpen(false);
    }
  }, [activeWorkspaceId, api, creatingRootPage, navigate]);

  const createSidebarChildPage = useCallback(
    async (parentDocument: DocumentItem) => {
      if (!activeWorkspaceId || sidebarCreatingParentId || sidebarDeletingDocumentId) {
        return;
      }
      setSidebarCreatingParentId(parentDocument.id);
      setSidebarActionError(null);
      setSidebarActionNotice(null);
      try {
        const parentTitle = parentDocument.title.trim();
        const created = await api.request<{ id: string }>(
          "/documents",
          {
            method: "POST",
            body: JSON.stringify({
              title: parentTitle ? `${parentTitle} 하위 페이지` : "새 페이지",
              body_markdown: "",
              source_type: "EDITOR",
              parent_document_id: parentDocument.id
            })
          },
          true
        );
        setSidebarPageMenuDocId(null);
        await loadSidebarDocuments();
        await loadSidebarFavorites();
        navigate(workspaceDocumentPath(activeWorkspaceId, created.id));
        setSidebarActionNotice({ tone: "success", message: "하위 페이지를 생성했습니다." });
      } catch (error) {
        setSidebarActionError(toUiError(error, "하위 페이지 생성에 실패했습니다"));
      } finally {
        setSidebarCreatingParentId(null);
      }
    },
    [activeWorkspaceId, api, loadSidebarDocuments, loadSidebarFavorites, navigate, sidebarCreatingParentId, sidebarDeletingDocumentId]
  );

  const copySidebarDocumentLink = useCallback(
    async (documentId: string) => {
      setSidebarActionError(null);
      try {
        const copiedUrl = activeWorkspaceId
          ? `${window.location.origin}${workspaceDocumentPath(activeWorkspaceId, documentId)}`
          : `${window.location.origin}/documents/${documentId}`;
        await copyTextToClipboard(copiedUrl);
        setSidebarActionNotice({ tone: "info", message: "문서 링크를 복사했습니다." });
      } catch (error) {
        setSidebarActionError(toUiError(error, "링크 복사에 실패했습니다"));
      } finally {
        setSidebarPageMenuDocId(null);
      }
    },
    [activeWorkspaceId]
  );

  const beginSidebarDocumentRename = useCallback((document: DocumentItem) => {
    setSidebarActionError(null);
    setSidebarActionNotice(null);
    setSidebarPageMenuDocId(document.id);
    setSidebarRenamingDocumentId(document.id);
    setSidebarRenameDraft(document.title);
  }, []);

  const cancelSidebarDocumentRename = useCallback(() => {
    if (sidebarRenamePendingDocumentId) {
      return;
    }
    setSidebarRenamingDocumentId(null);
    setSidebarRenameDraft("");
  }, [sidebarRenamePendingDocumentId]);

  const renameSidebarDocument = useCallback(
    async (document: DocumentItem) => {
      if (sidebarRenamePendingDocumentId) {
        return;
      }
      const nextTitle = sidebarRenameDraft.trim();
      if (!nextTitle || nextTitle === document.title) {
        setSidebarRenamingDocumentId(null);
        setSidebarRenameDraft("");
        return;
      }

      setSidebarActionError(null);
      setSidebarActionNotice(null);
      setSidebarRenamePendingDocumentId(document.id);

      try {
        const current = await api.request<DocumentItem>(`/documents/${document.id}`, {}, true);
        if (typeof current.version !== "number") {
          throw new Error("document_version_missing");
        }
        await api.request<void>(
          `/documents/${document.id}`,
          {
            method: "PATCH",
            body: JSON.stringify({
              version: current.version,
              title: nextTitle,
              body_markdown: current.body_markdown ?? ""
            })
          },
          true
        );
        setSidebarPageMenuDocId(null);
        setSidebarRenamingDocumentId(null);
        setSidebarRenameDraft("");
        await loadSidebarDocuments();
        setSidebarActionNotice({ tone: "success", message: "페이지 이름을 변경했습니다." });
      } catch (error) {
        setSidebarActionError(toUiError(error, "페이지 이름 변경에 실패했습니다"));
      } finally {
        setSidebarRenamePendingDocumentId(null);
      }
    },
    [api, loadSidebarDocuments, sidebarRenameDraft, sidebarRenamePendingDocumentId]
  );

  const deleteSidebarDocument = useCallback(
    async (document: DocumentItem) => {
      if (!activeWorkspaceId || sidebarDeletingDocumentId || sidebarCreatingParentId || sidebarFavoritePendingId) {
        return;
      }
      const confirmed = window.confirm(`"${document.title}" 페이지를 삭제하시겠습니까?`);
      if (!confirmed) {
        return;
      }

      setSidebarDeletingDocumentId(document.id);
      setSidebarActionError(null);
      setSidebarActionNotice(null);

      try {
        await api.request<void>(
          `/documents/${document.id}`,
          {
            method: "DELETE"
          },
          true
        );
        setSidebarPageMenuDocId(null);
        await loadSidebarDocuments();
        await loadSidebarFavorites();
        if (location.pathname.includes(`/doc/${document.id}`)) {
          navigate(workspaceRootPath(activeWorkspaceId));
        }
        setSidebarActionNotice({ tone: "success", message: "페이지를 삭제했습니다." });
      } catch (error) {
        setSidebarActionError(toUiError(error, "페이지 삭제에 실패했습니다"));
      } finally {
        setSidebarDeletingDocumentId(null);
      }
    },
    [
      activeWorkspaceId,
      api,
      loadSidebarDocuments,
      loadSidebarFavorites,
      location.pathname,
      navigate,
      sidebarCreatingParentId,
      sidebarDeletingDocumentId,
      sidebarFavoritePendingId
    ]
  );

  const toggleSidebarFavorite = useCallback(
    async (documentId: string, shouldFavorite: boolean) => {
      if (!activeWorkspaceId || sidebarFavoritePendingId || sidebarDeletingDocumentId || sidebarCreatingParentId) {
        return;
      }
      setSidebarFavoritePendingId(documentId);
      setSidebarActionError(null);
      setSidebarActionNotice(null);
      try {
        await api.request<void>(
          `/documents/${documentId}/favorite`,
          {
            method: shouldFavorite ? "POST" : "DELETE"
          },
          true
        );
        setSidebarFavoriteDocumentIds((previous) => {
          if (shouldFavorite) {
            return Array.from(new Set([documentId, ...previous]));
          }
          return previous.filter((value) => value !== documentId);
        });
        setSidebarActionNotice({
          tone: "success",
          message: shouldFavorite ? "즐겨찾기에 추가했습니다." : "즐겨찾기를 해제했습니다."
        });
      } catch (error) {
        setSidebarActionError(toUiError(error, "즐겨찾기 변경에 실패했습니다"));
      } finally {
        setSidebarFavoritePendingId(null);
        setSidebarPageMenuDocId(null);
      }
    },
    [activeWorkspaceId, api, sidebarCreatingParentId, sidebarDeletingDocumentId, sidebarFavoritePendingId]
  );

  const moveSidebarDocument = useCallback(
    async (documentId: string, parentDocumentId: string | null, mode: "CHILD" | "SIBLING" | "ROOT") => {
      if (
        !activeWorkspaceId ||
        sidebarMovePendingDocumentId ||
        sidebarDeletingDocumentId ||
        sidebarCreatingParentId ||
        sidebarFavoritePendingId
      ) {
        return;
      }
      const movingDocument = sidebarDocumentById.get(documentId);
      if (!movingDocument) {
        return;
      }
      if (movingDocument.parent_document_id === parentDocumentId) {
        setSidebarDraggingDocumentId(null);
        setSidebarDropTarget(null);
        return;
      }

      setSidebarMovePendingDocumentId(documentId);
      setSidebarActionError(null);
      setSidebarActionNotice(null);
      setSidebarPageMenuDocId(null);

      try {
        await api.request<void>(
          `/documents/${documentId}/move`,
          {
            method: "POST",
            body: JSON.stringify({ parent_document_id: parentDocumentId })
          },
          true
        );
        await loadSidebarDocuments();
        const moveText =
          mode === "CHILD"
            ? "하위로 이동했습니다."
            : mode === "SIBLING"
              ? "같은 레벨로 이동했습니다."
              : "루트 위치로 이동했습니다.";
        setSidebarActionNotice({ tone: "success", message: moveText });
      } catch (error) {
        setSidebarActionError(toUiError(error, "문서 이동에 실패했습니다"));
      } finally {
        setSidebarMovePendingDocumentId(null);
        setSidebarDraggingDocumentId(null);
        setSidebarDropTarget(null);
      }
    },
    [
      activeWorkspaceId,
      api,
      loadSidebarDocuments,
      sidebarCreatingParentId,
      sidebarDeletingDocumentId,
      sidebarDocumentById,
      sidebarFavoritePendingId,
      sidebarMovePendingDocumentId
    ]
  );

  const goToView = useCallback(
    (view: Exclude<SidebarViewKey, "workspace">) => {
      if (!activeWorkspaceId) {
        return;
      }
      navigate(view === "documents" ? workspaceRootPath(activeWorkspaceId) : workspaceViewPath(activeWorkspaceId, view));
      setIsPaletteOpen(false);
    },
    [activeWorkspaceId, navigate]
  );

  const handleWorkspaceChange = useCallback(
    (nextWorkspaceId: string) => {
      const nextWorkspace = workspaces.find((workspace) => workspace.id === nextWorkspaceId);
      if (!nextWorkspace) {
        return;
      }
      setWorkspace(nextWorkspace.id, nextWorkspace.name);
      saveLastWorkspaceId(nextWorkspace.id);
      navigate(workspaceRootPath(nextWorkspace.id));
      setIsPaletteOpen(false);
    },
    [navigate, setWorkspace, workspaces]
  );

  const goToWorkspaceHome = useCallback(() => {
    navigate("/workspace");
    setIsPaletteOpen(false);
    setIsSidebarOpen(false);
  }, [navigate]);

  const commandActions = useMemo(
    () => [
      {
        id: "action:new-page",
        label: "새 페이지 만들기",
        description: "루트 페이지를 즉시 생성합니다.",
        keywords: "new create page",
        execute: () => {
          void createRootPage();
        }
      },
      {
        id: "action:view-documents",
        label: "문서함 보기",
        description: "카드/리스트 뷰로 문서를 탐색합니다.",
        keywords: "documents list cards",
        execute: () => goToView("documents")
      },
      {
        id: "action:view-tree",
        label: "트리 보기",
        description: "가상 폴더 스냅샷을 탐색합니다.",
        keywords: "tree topic nodes",
        execute: () => goToView("tree")
      },
      {
        id: "action:view-questions",
        label: "질문 인박스 보기",
        description: "답변이 필요한 질문을 처리합니다.",
        keywords: "questions inbox review",
        execute: () => goToView("questions")
      },
      {
        id: "action:view-trash",
        label: "휴지통 보기",
        description: "삭제된 문서를 확인하고 복원합니다.",
        keywords: "trash restore deleted",
        execute: () => goToView("trash")
      }
    ],
    [createRootPage, goToView]
  );

  const filteredPaletteActions = useMemo(() => {
    const query = paletteQuery.trim().toLowerCase();
    if (!query) {
      return commandActions;
    }
    return commandActions.filter(
      (action) =>
        action.label.toLowerCase().includes(query) ||
        action.description.toLowerCase().includes(query) ||
        action.keywords.includes(query)
    );
  }, [commandActions, paletteQuery]);

  const filteredPaletteDocuments = useMemo(() => {
    if (!activeWorkspaceId) {
      return [];
    }
    const query = paletteQuery.trim().toLowerCase();
    const source = query
      ? sidebarDocuments.filter((document) => document.title.toLowerCase().includes(query))
      : [...sidebarDocuments].sort(sortDocumentsByRecency);
    return source.slice(0, COMMAND_PALETTE_MAX_RESULTS).map((document) => ({
      id: `doc:${document.id}`,
      label: document.title,
      description: "페이지 열기",
      execute: () => {
        navigate(workspaceDocumentPath(activeWorkspaceId, document.id));
        setIsPaletteOpen(false);
      }
    }));
  }, [activeWorkspaceId, navigate, paletteQuery, sidebarDocuments]);

  const paletteItems = useMemo(() => [...filteredPaletteActions, ...filteredPaletteDocuments], [filteredPaletteActions, filteredPaletteDocuments]);

  const renderPageTree = (nodes: SidebarPageNode[], depth: number): React.ReactNode =>
    nodes.map((node) => {
      const isActive = location.pathname.includes(`/doc/${node.doc.id}`);
      const isCreatingChild = sidebarCreatingParentId === node.doc.id;
      const isDeleting = sidebarDeletingDocumentId === node.doc.id;
      const isFavoritePending = sidebarFavoritePendingId === node.doc.id;
      const isMoving = sidebarMovePendingDocumentId === node.doc.id;
      const isRenamePending = sidebarRenamePendingDocumentId === node.doc.id;
      const isFavorite = sidebarFavoriteSet.has(node.doc.id);
      const menuOpen = sidebarPageMenuDocId === node.doc.id;
      const isRenaming = sidebarRenamingDocumentId === node.doc.id;
      const menuDisabled =
        isCreatingChild ||
        isDeleting ||
        isFavoritePending ||
        isMoving ||
        isRenamePending ||
        !!creatingRootPage ||
        !!sidebarMovePendingDocumentId;
      const isDragged = sidebarDraggingDocumentId === node.doc.id;
      const isChildDropTarget = sidebarDropTarget?.mode === "CHILD" && sidebarDropTarget.targetDocumentId === node.doc.id;
      const isSiblingDropTarget = sidebarDropTarget?.mode === "SIBLING" && sidebarDropTarget.targetDocumentId === node.doc.id;
      return (
        <li className={`sidebar-page-item${menuOpen ? " is-menu-open" : ""}`} key={node.doc.id}>
          <div
            aria-hidden="true"
            className={`sidebar-drop-zone sidebar-drop-zone-sibling${isSiblingDropTarget ? " is-active" : ""}`}
            onDragOver={(event) => {
              if (!sidebarDraggingDocumentId || sidebarDraggingDocumentId === node.doc.id) {
                return;
              }
              event.preventDefault();
              setSidebarDropTarget({ targetDocumentId: node.doc.id, mode: "SIBLING" });
            }}
            onDrop={(event) => {
              event.preventDefault();
              const draggingId = sidebarDraggingDocumentId ?? event.dataTransfer.getData("text/plain");
              if (!draggingId || draggingId === node.doc.id) {
                return;
              }
              void moveSidebarDocument(draggingId, node.doc.parent_document_id ?? null, "SIBLING");
            }}
          />

          <div
            className={`sidebar-page-row${isActive ? " is-active" : ""}${isDragged ? " is-dragging" : ""}${isChildDropTarget ? " is-drop-child" : ""}${
              menuOpen ? " is-menu-open" : ""
            }`}
            data-sidebar-menu-root={node.doc.id}
            onDragOver={(event) => {
              if (!sidebarDraggingDocumentId || sidebarDraggingDocumentId === node.doc.id) {
                return;
              }
              event.preventDefault();
              setSidebarDropTarget({ targetDocumentId: node.doc.id, mode: "CHILD" });
            }}
            onDrop={(event) => {
              event.preventDefault();
              const draggingId = sidebarDraggingDocumentId ?? event.dataTransfer.getData("text/plain");
              if (!draggingId || draggingId === node.doc.id) {
                return;
              }
              void moveSidebarDocument(draggingId, node.doc.id, "CHILD");
            }}
          >
            <button
              className={`sidebar-page-button${isActive ? " is-active" : ""}`}
              draggable={!menuDisabled}
              onClick={() => {
                if (!activeWorkspaceId) {
                  return;
                }
                navigate(workspaceDocumentPath(activeWorkspaceId, node.doc.id));
              }}
              onDragEnd={() => {
                setSidebarDraggingDocumentId(null);
                setSidebarDropTarget(null);
              }}
              onDragStart={(event) => {
                event.dataTransfer.effectAllowed = "move";
                event.dataTransfer.setData("text/plain", node.doc.id);
                setSidebarDraggingDocumentId(node.doc.id);
                setSidebarDropTarget(null);
                setSidebarPageMenuDocId(null);
              }}
              style={{ paddingLeft: `${12 + depth * 16}px` }}
              type="button"
            >
              <span className="sidebar-page-dot">•</span>
              {isFavorite ? <span className="sidebar-page-favorite" title="즐겨찾기">★</span> : null}
              <span className="sidebar-page-title">{node.doc.title}</span>
            </button>
            <div className={`sidebar-page-row-actions${menuOpen ? " is-menu-open" : ""}`}>
              <button
                aria-label="하위 페이지 추가"
                className="sidebar-page-action"
                disabled={menuDisabled}
                onClick={() => {
                  void createSidebarChildPage(node.doc);
                }}
                title="하위 페이지 추가"
                type="button"
              >
                +
              </button>
              <button
                aria-expanded={menuOpen}
                aria-label="페이지 메뉴"
                className="sidebar-page-action"
                disabled={menuDisabled}
                onClick={() => {
                  setSidebarPageMenuDocId((previous) => {
                    const next = previous === node.doc.id ? null : node.doc.id;
                    if (next !== node.doc.id) {
                      setSidebarRenamingDocumentId(null);
                      setSidebarRenameDraft("");
                    }
                    return next;
                  });
                }}
                title="페이지 메뉴"
                type="button"
              >
                ...
              </button>
              {menuOpen ? (
                <div className="sidebar-page-menu" role="menu">
                  <button
                    className="sidebar-page-menu-item"
                    onClick={() => {
                      if (!activeWorkspaceId) {
                        return;
                      }
                      navigate(workspaceDocumentPath(activeWorkspaceId, node.doc.id));
                      setSidebarPageMenuDocId(null);
                    }}
                    role="menuitem"
                    type="button"
                  >
                    문서 수정
                  </button>
                  <button
                    className="sidebar-page-menu-item"
                    onClick={() => {
                      if (!activeWorkspaceId) {
                        return;
                      }
                      navigate(workspaceDocumentDetailPath(activeWorkspaceId, node.doc.id));
                      setSidebarPageMenuDocId(null);
                    }}
                    role="menuitem"
                    type="button"
                  >
                    문서 상세 보기
                  </button>
                  <button
                    className="sidebar-page-menu-item"
                    onClick={() => {
                      void createSidebarChildPage(node.doc);
                    }}
                    role="menuitem"
                    type="button"
                  >
                    하위 페이지 추가
                  </button>
                  <button
                    className="sidebar-page-menu-item"
                    disabled={isFavoritePending}
                    onClick={() => {
                      void toggleSidebarFavorite(node.doc.id, !isFavorite);
                    }}
                    role="menuitem"
                    type="button"
                  >
                    {isFavorite ? "즐겨찾기 해제" : "즐겨찾기에 추가"}
                  </button>
                  <button
                    className="sidebar-page-menu-item"
                    onClick={() => {
                      void copySidebarDocumentLink(node.doc.id);
                    }}
                    role="menuitem"
                    type="button"
                  >
                    링크 복사
                  </button>
                  {isRenaming ? (
                    <div className="sidebar-page-inline-rename">
                      <input
                        aria-label="새 페이지 이름"
                        className="field-input inline-rename-input"
                        onChange={(event) => setSidebarRenameDraft(event.target.value)}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            event.preventDefault();
                            void renameSidebarDocument(node.doc);
                          } else if (event.key === "Escape") {
                            event.preventDefault();
                            cancelSidebarDocumentRename();
                          }
                        }}
                        value={sidebarRenameDraft}
                      />
                      <div className="inline-rename-actions">
                        <button
                          className="btn btn-ghost btn-small inline-rename-button"
                          disabled={isRenamePending}
                          onClick={() => {
                            cancelSidebarDocumentRename();
                          }}
                          type="button"
                        >
                          취소
                        </button>
                        <button
                          className="btn btn-primary btn-small inline-rename-button"
                          disabled={isRenamePending || !sidebarRenameDraft.trim()}
                          onClick={() => {
                            void renameSidebarDocument(node.doc);
                          }}
                          type="button"
                        >
                          {isRenamePending ? "저장 중..." : "저장"}
                        </button>
                      </div>
                    </div>
                  ) : (
                    <button
                      className="sidebar-page-menu-item"
                      onClick={() => {
                        beginSidebarDocumentRename(node.doc);
                      }}
                      role="menuitem"
                      type="button"
                    >
                      이름 바꾸기
                    </button>
                  )}
                  <button
                    className="sidebar-page-menu-item is-danger"
                    disabled={isDeleting || isRenamePending}
                    onClick={() => {
                      void deleteSidebarDocument(node.doc);
                    }}
                    role="menuitem"
                    type="button"
                  >
                    휴지통으로 이동
                  </button>
                </div>
              ) : null}
            </div>
          </div>
          {node.children.length > 0 ? <ul className="sidebar-page-list">{renderPageTree(node.children, depth + 1)}</ul> : null}
        </li>
      );
    });

  const countSidebarNodeDocuments = (node: EditorNodeTree): number => {
    return node.documents.length + node.children.reduce((acc, child) => acc + countSidebarNodeDocuments(child), 0);
  };

  const renderSidebarNodeTree = (nodes: EditorNodeTree[], depth: number): React.ReactNode =>
    nodes.map((node) => {
      const totalDocuments = countSidebarNodeDocuments(node);
      return (
        <li className="sidebar-node-item" key={node.id}>
          <div className="sidebar-node-row" style={{ paddingLeft: `${12 + depth * 16}px` }}>
            <span className="sidebar-node-label">
              {node.label}
              {node.locked ? <span className="lock-badge">잠금</span> : null}
            </span>
            <span className="tree-node-count">{totalDocuments}</span>
          </div>
          {node.documents.length > 0 ? (
            <ul className="sidebar-node-doc-list">
              {node.documents.map((document) => {
                const isActive = location.pathname.includes(`/doc/${document.id}`);
                return (
                  <li key={`${node.id}:${document.id}`}>
                    <button
                      className={`sidebar-node-doc-button${isActive ? " is-active" : ""}`}
                      onClick={() => {
                        if (!activeWorkspaceId) {
                          return;
                        }
                        navigate(workspaceDocumentPath(activeWorkspaceId, document.id));
                      }}
                      style={{ paddingLeft: `${24 + depth * 16}px` }}
                      type="button"
                    >
                      <span className="sidebar-node-doc-title">{document.title}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : null}
          {node.children.length > 0 ? <ul className="sidebar-node-list">{renderSidebarNodeTree(node.children, depth + 1)}</ul> : null}
        </li>
      );
    });

  const beginSidebarResize = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (typeof window === "undefined" || window.matchMedia("(max-width: 1080px)").matches) {
        return;
      }
      event.preventDefault();

      const startX = event.clientX;
      const startWidth = sidebarWidth;
      setIsSidebarResizing(true);

      const onPointerMove = (moveEvent: PointerEvent) => {
        const delta = moveEvent.clientX - startX;
        applySidebarWidth(startWidth + delta);
      };

      const onPointerEnd = () => {
        setIsSidebarResizing(false);
        window.removeEventListener("pointermove", onPointerMove);
        window.removeEventListener("pointerup", onPointerEnd);
        window.removeEventListener("pointercancel", onPointerEnd);
      };

      window.addEventListener("pointermove", onPointerMove);
      window.addEventListener("pointerup", onPointerEnd);
      window.addEventListener("pointercancel", onPointerEnd);
    },
    [applySidebarWidth, sidebarWidth]
  );

  const workspaceLayoutStyle = useMemo(
    () =>
      ({
        "--workspace-sidebar-width": `${clampSidebarWidth(sidebarWidth)}px`
      }) as React.CSSProperties,
    [sidebarWidth]
  );

  return (
    <div className={`workspace-layout${isSidebarResizing ? " is-resizing" : ""}`} style={workspaceLayoutStyle}>
      <div className={`workspace-sidebar-backdrop${isSidebarOpen ? " is-open" : ""}`} onClick={() => setIsSidebarOpen(false)} role="presentation" />
      <aside className={`workspace-sidebar${isSidebarOpen ? " is-open" : ""}`}>
        <div className="workspace-sidebar-header">
          <button
            aria-label="워크스페이스 홈으로 이동"
            className="brand brand-link"
            onClick={goToWorkspaceHome}
            type="button"
          >
            <span className="brand-mark">오</span>
            <div className="brand-text">
              <strong>오토독 트리</strong>
              <span>문서 워크스페이스</span>
            </div>
          </button>
          <label className="sidebar-workspace-switcher" htmlFor="workspace-switcher">
            <span>WORKSPACE</span>
            <select
              className="field-input"
              id="workspace-switcher"
              onChange={(event) => handleWorkspaceChange(event.target.value)}
              value={activeWorkspaceId ?? ""}
            >
              {workspaces.map((workspace) => (
                <option key={workspace.id} value={workspace.id}>
                  {workspace.name}
                </option>
              ))}
            </select>
          </label>
        </div>

        <div className="sidebar-section">
          <p className="sidebar-section-label">Quick Actions</p>
          <div className="sidebar-quick-actions">
            <button className="btn btn-secondary btn-small" disabled={!activeWorkspaceId || creatingRootPage} onClick={() => void createRootPage()} type="button">
              <SidebarMenuIcon name="plus" />
              <span className="sidebar-menu-item-label">{creatingRootPage ? "생성 중..." : "새 페이지"}</span>
            </button>
            <button
              className="btn btn-ghost btn-small"
              onClick={() => {
                setIsPaletteOpen(true);
              }}
              type="button"
            >
              <SidebarMenuIcon name="search" />
              <span className="sidebar-menu-item-label">검색 / Cmd+K</span>
            </button>
          </div>
        </div>

        {pagesBrowseMode === "DOCUMENT" ? (
          <div className="sidebar-section sidebar-section-favorites">
            <div className="sidebar-section-title-row">
              <p className="sidebar-section-label">Favorites</p>
              <span className="sidebar-pill">{sidebarFavoriteDocumentIds.length}</span>
            </div>
            <ErrorPanel
              error={sidebarFavoriteError}
              onRetry={() => {
                void loadSidebarFavorites();
              }}
            />
            <ul className="sidebar-page-list sidebar-favorites-list">
              {favoriteSidebarDocuments.length > 0 ? (
                favoriteSidebarDocuments.map((document) => {
                  const isActive = location.pathname.includes(`/doc/${document.id}`);
                  return (
                    <li className="sidebar-page-item" key={`favorite-${document.id}`}>
                      <button
                        className={`sidebar-page-button${isActive ? " is-active" : ""}`}
                        onClick={() => {
                          if (!activeWorkspaceId) {
                            return;
                          }
                          navigate(workspaceDocumentPath(activeWorkspaceId, document.id));
                        }}
                        type="button"
                      >
                        <span className="sidebar-page-favorite" title="즐겨찾기">★</span>
                        <span className="sidebar-page-title">{document.title}</span>
                      </button>
                    </li>
                  );
                })
              ) : (
                <li className="sidebar-empty">즐겨찾기 문서가 없습니다.</li>
              )}
            </ul>
          </div>
        ) : null}

        <div className="sidebar-section sidebar-section-pages">
          <div className="sidebar-section-title-row">
            <p className="sidebar-section-label">Pages</p>
            <span className="sidebar-pill">{pagesBrowseMode === "DOCUMENT" ? sidebarDocuments.length : sidebarNodeTreeResponse?.nodes.length ?? 0}</span>
          </div>
          <div className="editor-view-toggle" role="tablist" aria-label="페이지 보기 모드 전환">
            <button
              aria-selected={pagesBrowseMode === "DOCUMENT"}
              className={`editor-view-toggle-button${pagesBrowseMode === "DOCUMENT" ? " is-active" : ""}`}
              onClick={() => setPagesBrowseMode("DOCUMENT")}
              role="tab"
              type="button"
            >
              문서로 분류
            </button>
            <button
              aria-selected={pagesBrowseMode === "NODE"}
              className={`editor-view-toggle-button${pagesBrowseMode === "NODE" ? " is-active" : ""}`}
              onClick={() => setPagesBrowseMode("NODE")}
              role="tab"
              type="button"
            >
              노드로 분류
            </button>
          </div>
          <input
            className="field-input sidebar-filter-input"
            onChange={(event) => setSidebarQuery(event.target.value)}
            placeholder={pagesBrowseMode === "DOCUMENT" ? "페이지 제목 검색" : "노드/문서 검색"}
            value={sidebarQuery}
          />
          <NoticePanel notice={sidebarActionNotice} />
          <ErrorPanel
            error={sidebarActionError}
            onRetry={() => {
              void loadSidebarDocuments();
            }}
          />
          {pagesBrowseMode === "DOCUMENT" ? (
            <>
              <ErrorPanel error={documentError} />
              <div
                aria-hidden="true"
                className={`sidebar-root-drop-zone${sidebarDropTarget?.mode === "ROOT" ? " is-active" : ""}${sidebarDraggingDocumentId ? " is-visible" : ""}`}
                onDragOver={(event) => {
                  if (!sidebarDraggingDocumentId) {
                    return;
                  }
                  event.preventDefault();
                  setSidebarDropTarget({ targetDocumentId: null, mode: "ROOT" });
                }}
                onDrop={(event) => {
                  event.preventDefault();
                  const draggingId = sidebarDraggingDocumentId ?? event.dataTransfer.getData("text/plain");
                  if (!draggingId) {
                    return;
                  }
                  void moveSidebarDocument(draggingId, null, "ROOT");
                }}
              >
                루트로 이동하려면 여기에 드롭하세요
              </div>
              <ul className="sidebar-page-list">
                {filteredPageTreeRoots.length > 0 ? (
                  renderPageTree(filteredPageTreeRoots, 0)
                ) : (
                  <li className="sidebar-empty">페이지가 없습니다.</li>
                )}
              </ul>
            </>
          ) : (
            <>
              <ErrorPanel
                error={sidebarNodeError}
                onRetry={() => {
                  void loadSidebarNodeTree();
                }}
              />
              {loadingSidebarNodeTree ? <p className="muted">노드 분류를 불러오는 중입니다...</p> : null}
              <ul className="sidebar-page-list sidebar-node-scroll">
                {!loadingSidebarNodeTree && filteredSidebarNodeTrees.length > 0 ? (
                  renderSidebarNodeTree(filteredSidebarNodeTrees, 0)
                ) : !loadingSidebarNodeTree ? (
                  <li className="sidebar-empty">노드가 없습니다.</li>
                ) : null}
              </ul>
            </>
          )}
        </div>

        <div className="sidebar-section">
          <p className="sidebar-section-label">Views</p>
          <div className="sidebar-view-list">
            <button
              className={`sidebar-view-button${activeView === "documents" ? " is-active" : ""}`}
              onClick={() => goToView("documents")}
              type="button"
            >
              <SidebarMenuIcon className="sidebar-menu-item-icon sidebar-menu-item-icon-view" name="document" />
              <span className="sidebar-menu-item-label">Documents</span>
            </button>
            <button
              className={`sidebar-view-button${activeView === "tree" ? " is-active" : ""}`}
              onClick={() => goToView("tree")}
              type="button"
            >
              <SidebarMenuIcon className="sidebar-menu-item-icon sidebar-menu-item-icon-view" name="tree" />
              <span className="sidebar-menu-item-label">Tree</span>
            </button>
            <button
              className={`sidebar-view-button${activeView === "questions" ? " is-active" : ""}`}
              onClick={() => goToView("questions")}
              type="button"
            >
              <SidebarMenuIcon className="sidebar-menu-item-icon sidebar-menu-item-icon-view" name="question" />
              <span className="sidebar-menu-item-label">Questions</span>
            </button>
            <button
              className={`sidebar-view-button${activeView === "trash" ? " is-active" : ""}`}
              onClick={() => goToView("trash")}
              type="button"
            >
              <SidebarMenuIcon className="sidebar-menu-item-icon sidebar-menu-item-icon-view" name="trash" />
              <span className="sidebar-menu-item-label">Trash</span>
            </button>
          </div>
        </div>

        <div className="sidebar-footer">
          <ErrorPanel error={workspaceError} />
          <button className="btn btn-ghost btn-small" onClick={() => navigate("/workspace")} type="button">
            워크스페이스 설정
          </button>
          <button
            className="btn btn-secondary btn-small"
            onClick={() => {
              clearTokens();
              navigate("/login");
            }}
            type="button"
          >
            로그아웃
          </button>
        </div>
      </aside>

      <div
        aria-label="사이드바 폭 조절"
        aria-orientation="vertical"
        aria-valuemax={SIDEBAR_WIDTH_MAX}
        aria-valuemin={SIDEBAR_WIDTH_MIN}
        aria-valuenow={sidebarWidth}
        className="workspace-sidebar-resizer"
        onKeyDown={(event) => {
          if (event.key === "ArrowLeft") {
            event.preventDefault();
            applySidebarWidth(sidebarWidth - SIDEBAR_WIDTH_STEP);
            return;
          }
          if (event.key === "ArrowRight") {
            event.preventDefault();
            applySidebarWidth(sidebarWidth + SIDEBAR_WIDTH_STEP);
            return;
          }
          if (event.key === "Home") {
            event.preventDefault();
            applySidebarWidth(SIDEBAR_WIDTH_MIN);
            return;
          }
          if (event.key === "End") {
            event.preventDefault();
            applySidebarWidth(SIDEBAR_WIDTH_MAX);
          }
        }}
        onPointerDown={beginSidebarResize}
        role="separator"
        tabIndex={0}
      />

      <div className="workspace-main">
        <header className="workspace-header panel panel-compact">
          <div className="workspace-header-menu-wrap">
            <button
              aria-expanded={isSidebarOpen}
              className="btn btn-ghost btn-small sidebar-menu-button"
              onClick={() => {
                setIsSidebarOpen((previous) => !previous);
              }}
              type="button"
            >
              메뉴
            </button>
            {isSidebarOpen ? (
              <div className="header-menu-panel">
                <strong>{state.workspaceName ?? "워크스페이스"}</strong>
                <button
                  className="header-menu-item"
                  onClick={() => {
                    goToView("documents");
                    setIsSidebarOpen(false);
                  }}
                  type="button"
                >
                  Documents
                </button>
                <button
                  className="header-menu-item"
                  onClick={() => {
                    goToView("tree");
                    setIsSidebarOpen(false);
                  }}
                  type="button"
                >
                  Tree
                </button>
                <button
                  className="header-menu-item"
                  onClick={() => {
                    goToView("questions");
                    setIsSidebarOpen(false);
                  }}
                  type="button"
                >
                  Questions
                </button>
                <button
                  className="header-menu-item"
                  onClick={() => {
                    goToView("trash");
                    setIsSidebarOpen(false);
                  }}
                  type="button"
                >
                  Trash
                </button>
                <button
                  className="header-menu-item"
                  onClick={() => {
                    navigate("/workspace");
                    setIsSidebarOpen(false);
                  }}
                  type="button"
                >
                  워크스페이스 설정
                </button>
                <button
                  className="header-menu-item"
                  onClick={() => {
                    clearTokens();
                    navigate("/login");
                    setIsSidebarOpen(false);
                  }}
                  type="button"
                >
                  로그아웃
                </button>
              </div>
            ) : null}
          </div>
          <div className="workspace-header-crumbs">
            <span>{state.workspaceName ?? "Workspace"}</span>
            <span>/</span>
            <strong>
              {activeView === "tree"
                ? "Tree"
                : activeView === "questions"
                  ? "Questions"
                  : activeView === "trash"
                    ? "Trash"
                    : activeView === "workspace"
                      ? "Settings"
                      : "Documents"}
            </strong>
          </div>
          <button
            className="btn btn-secondary btn-small"
            onClick={() => {
              setIsPaletteOpen(true);
            }}
            type="button"
          >
            Search / Cmd+K
          </button>
        </header>
        <main className="page-stack">{children}</main>
      </div>

      {isPaletteOpen ? (
        <div
          className="command-palette-backdrop"
          onClick={() => {
            setIsPaletteOpen(false);
          }}
          role="presentation"
        >
          <div
            className="command-palette"
            onClick={(event) => event.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label="검색 및 커맨드 팔레트"
          >
            <input
              className="field-input command-palette-input"
              onChange={(event) => setPaletteQuery(event.target.value)}
              placeholder="문서 검색 또는 명령 입력..."
              ref={paletteInputRef}
              value={paletteQuery}
            />
            <ul className="command-palette-results">
              {paletteItems.length > 0 ? (
                paletteItems.map((item) => (
                  <li key={item.id}>
                    <button
                      className="command-palette-item"
                      onClick={() => {
                        item.execute();
                        setIsPaletteOpen(false);
                      }}
                      type="button"
                    >
                      <strong>{item.label}</strong>
                      <span>{item.description}</span>
                    </button>
                  </li>
                ))
              ) : (
                <li className="sidebar-empty">결과가 없습니다.</li>
              )}
            </ul>
          </div>
        </div>
      ) : null}
    </div>
  );
}



function CommandPalette({ workspaceId, accessToken }: { workspaceId: string | null; accessToken: string | null }) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResponse["items"]>([]);
  const [history, setHistory] = useState<PaletteHistoryResponse["items"]>([]);
  const [selected, setSelected] = useState(0);
  const [mode, setMode] = useState<"bm25" | "hybrid">((localStorage.getItem("palette_mode") as "bm25" | "hybrid") || "bm25");
  const [titleOnly, setTitleOnly] = useState(localStorage.getItem("palette_title_only") === "1");

  const commands = useMemo(() => [
    { key: "new_page", label: "새 페이지", run: () => navigate("/editor") },
    { key: "inbox", label: "문서함", run: () => navigate("/inbox") },
    { key: "tree", label: "트리", run: () => navigate("/tree") },
    { key: "questions", label: "질문함", run: () => navigate("/questions") },
    { key: "workspace", label: "워크스페이스", run: () => navigate("/workspace") }
  ], [navigate]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((v) => !v);
      }
      if (open && e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  useEffect(() => {
    localStorage.setItem("palette_mode", mode);
    localStorage.setItem("palette_title_only", titleOnly ? "1" : "0");
  }, [mode, titleOnly]);

  useEffect(() => {
    if (!open || !workspaceId || !accessToken) return;
    fetch(`/api/v1/search/history?limit=40`, { headers: { Authorization: `Bearer ${accessToken}`, "X-Workspace-Id": workspaceId } })
      .then((r) => r.json())
      .then((data: PaletteHistoryResponse) => setHistory(data.items ?? []))
      .catch(() => setHistory([]));
  }, [open, workspaceId, accessToken]);

  useEffect(() => {
    if (!open || !workspaceId || !accessToken || !query.trim()) {
      setResults([]);
      return;
    }
    const params = new URLSearchParams({ q: query, mode, titleOnly: String(titleOnly), sort: "relevance" });
    fetch(`/api/v1/search?${params.toString()}`, { headers: { Authorization: `Bearer ${accessToken}`, "X-Workspace-Id": workspaceId } })
      .then((r) => r.json())
      .then((data: SearchResponse) => setResults(data.items ?? []))
      .catch(() => setResults([]));
  }, [open, query, workspaceId, accessToken, mode, titleOnly]);

  const historyGroups = useMemo(() => {
    const now = new Date();
    const buckets: Record<string, PaletteHistoryResponse["items"]> = { "오늘": [], "어제": [], "지난 7일": [] };
    history.forEach((item) => {
      const d = new Date(item.created_at);
      const diffDays = Math.floor((now.getTime() - d.getTime()) / (1000 * 60 * 60 * 24));
      if (diffDays <= 0) buckets["오늘"].push(item);
      else if (diffDays === 1) buckets["어제"].push(item);
      else if (diffDays <= 7) buckets["지난 7일"].push(item);
    });
    return buckets;
  }, [history]);

  const items = query.trim()
    ? [...commands.filter((c) => c.label.toLowerCase().includes(query.toLowerCase())).map((c) => ({ kind: "command" as const, c })), ...results.map((r) => ({ kind: "doc" as const, r }))]
    : commands.map((c) => ({ kind: "command" as const, c }));

  const executeHistory = (payload: Record<string, unknown>) => {
    if (!workspaceId || !accessToken) return;
    void fetch(`/api/v1/search/history`, {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}`, "X-Workspace-Id": workspaceId, "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
  };

  const onEnter = () => {
    const picked = items[selected];
    if (!picked) return;
    if (picked.kind === "command") {
      picked.c.run();
      executeHistory({ eventType: "COMMAND", commandKey: picked.c.key, queryText: query || null });
    } else {
      navigate(`/documents/${picked.r.document_id}`);
      executeHistory({ eventType: "OPEN_DOCUMENT", documentId: picked.r.document_id, queryText: query || null });
    }
    setOpen(false);
  };

  if (!open) return <button className="btn btn-secondary" type="button" onClick={() => setOpen(true)}>⌘K</button>;

  return (
    <div className="palette-overlay" onClick={() => setOpen(false)}>
      <div className="palette-modal" onClick={(e) => e.stopPropagation()}>
        <input
          autoFocus
          className="field-input"
          placeholder="문서 검색 또는 명령 입력…"
          value={query}
          onChange={(e) => { setQuery(e.target.value); setSelected(0); }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") { e.preventDefault(); setSelected((v) => Math.min(items.length - 1, v + 1)); }
            if (e.key === "ArrowUp") { e.preventDefault(); setSelected((v) => Math.max(0, v - 1)); }
            if (e.key === "Enter") { e.preventDefault(); onEnter(); }
          }}
        />
        <div className="palette-filters">
          <label><input type="checkbox" checked={titleOnly} onChange={(e) => setTitleOnly(e.target.checked)} /> 제목만</label>
          <label>모드 <select value={mode} onChange={(e) => setMode(e.target.value as "bm25" | "hybrid")}><option value="bm25">bm25</option><option value="hybrid">hybrid</option></select></label>
        </div>
        {!query.trim() ? (
          <div className="palette-history">
            {Object.entries(historyGroups).map(([group, items]) => (
              <div key={group}><strong>{group}</strong><ul>{items.map((h) => <li key={h.id}>{h.query_text || h.command_key || h.document_id}</li>)}</ul></div>
            ))}
          </div>
        ) : (
          <ul className="search-results">
            {items.map((item, idx) => (
              <li className={`search-result-item ${idx === selected ? "is-selected" : ""}`} key={item.kind === "command" ? item.c.key : item.r.document_id}>
                {item.kind === "command" ? `⌘ ${item.c.label}` : `${item.r.title} · ${(item.r.breadcrumb || []).join(" / ")}`}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<UiError | null>(null);
  const { setTokens } = useSession();
  const navigate = useNavigate();

  const api = useMemo(
    () =>
      createApiClient({
        getToken: () => null,
        getWorkspaceId: () => null,
        onUnauthorized: async () => false
      }),
    []
  );

  return (
    <div className="auth-shell">
      <form
        className="panel auth-card"
        onSubmit={async (event) => {
          event.preventDefault();
          setError(null);
          try {
          const response = await api.request<AuthResponse>("/auth/login", {
            method: "POST",
            body: JSON.stringify({ email, password })
          });
          setTokens(response.access_token, response.refresh_token);
          navigate("/");
        } catch (e) {
          setError(toUiError(e, "로그인에 실패했습니다"));
        }
      }}
    >
      <div className="auth-eyebrow">오토독 트리</div>
      <h1 className="auth-title">다시 오신 것을 환영합니다</h1>
      <p className="auth-subtitle">워크스페이스 계정으로 로그인해 문서를 자동으로 정리하세요.</p>

      <label className="field-label" htmlFor="login-email">
          이메일
      </label>
        <input
          className="field-input"
          id="login-email"
          onChange={(e) => setEmail(e.target.value)}
          placeholder="이메일 주소"
          type="email"
          value={email}
        />

      <label className="field-label" htmlFor="login-password">
          비밀번호
      </label>
        <input
          className="field-input"
          id="login-password"
          onChange={(e) => setPassword(e.target.value)}
          placeholder="비밀번호"
          type="password"
          value={password}
        />

      <button className="btn btn-primary btn-block" type="submit">
          로그인
      </button>

        <ErrorPanel error={error} />
      </form>
    </div>
  );
}

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { state } = useSession();
  if (!state.accessToken) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  const { state, setTokens, clearTokens, setWorkspace } = useSession();

  const api = useMemo(
    () =>
      createApiClient({
        getToken: () => state.accessToken,
        getWorkspaceId: () => state.workspaceId,
        onUnauthorized: async () => {
          if (!state.refreshToken) {
            clearTokens();
            return false;
          }
          try {
            const refreshed = await fetch(`${import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1"}/auth/refresh`, {
              method: "POST",
              headers: {
                "Content-Type": "application/json"
              },
              body: JSON.stringify({ refresh_token: state.refreshToken })
            });
            if (!refreshed.ok) {
              clearTokens();
              return false;
            }
            const payload = (await refreshed.json()) as AuthResponse;
            setTokens(payload.access_token, payload.refresh_token);
            return true;
          } catch {
            clearTokens();
            return false;
          }
        }
      }),
    [clearTokens, setTokens, state.accessToken, state.refreshToken, state.workspaceId]
  );

  useEffect(() => {
    saveLastWorkspaceId(state.workspaceId);
  }, [state.workspaceId]);

  useEffect(() => {
    if (!state.accessToken || state.workspaceId) {
      return;
    }
    let active = true;
    void (async () => {
      try {
        const response = await api.request<WorkspaceListResponse>("/workspaces");
        if (!active || response.items.length === 0) {
          return;
        }
        const savedWorkspaceId = loadLastWorkspaceId();
        const nextWorkspace = response.items.find((workspace) => workspace.id === savedWorkspaceId) ?? response.items[0];
        setWorkspace(nextWorkspace.id, nextWorkspace.name);
      } catch {
        // Ignore bootstrap errors and keep workspace selection page reachable.
      }
    })();
    return () => {
      active = false;
    };
  }, [api, setWorkspace, state.accessToken, state.workspaceId]);

  function WorkspacePage() {
    const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
    const [name, setName] = useState("내 워크스페이스");
    const [error, setError] = useState<UiError | null>(null);
    const navigate = useNavigate();

    const loadWorkspaces = useCallback(async () => {
      setError(null);
      try {
        const response = await api.request<WorkspaceListResponse>("/workspaces");
        setWorkspaces(response.items);
      } catch (e) {
        setError(toUiError(e, "워크스페이스 목록을 불러오지 못했습니다"));
      }
    }, [api]);

    useEffect(() => {
      void loadWorkspaces();
    }, [loadWorkspaces]);

    return (
      <Layout api={api}>
        <section className="panel">
          <PageHeader title="워크스페이스" subtitle="문서를 탐색하거나 편집하기 전에 테넌트 컨텍스트를 선택하세요." />
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadWorkspaces();
            }}
          />

          <div className="field-row">
            <div className="field-grow">
              <label className="field-label" htmlFor="workspace-name">
                새 워크스페이스 이름
              </label>
              <input className="field-input" id="workspace-name" onChange={(e) => setName(e.target.value)} value={name} />
            </div>
            <button
              className="btn btn-primary"
              onClick={async () => {
                try {
                  await api.request("/workspaces", {
                    method: "POST",
                    body: JSON.stringify({ name })
                  });
                  await loadWorkspaces();
                } catch (e) {
                  setError(toUiError(e, "워크스페이스 생성에 실패했습니다"));
                }
              }}
              type="button"
            >
              워크스페이스 생성
            </button>
          </div>

          {workspaces.length === 0 ? (
            <EmptyState title="워크스페이스가 없습니다" description="워크스페이스를 만들어 문서 정리를 시작하세요." />
          ) : (
            <ul className="workspace-list">
              {workspaces.map((ws) => {
                const roleName = ws.role.toLowerCase();
                const isSelected = state.workspaceId === ws.id;
                return (
                  <li className={`workspace-item${isSelected ? " is-selected" : ""}`} key={ws.id}>
                    <button
                      className="workspace-button"
                      onClick={() => {
                        setWorkspace(ws.id, ws.name);
                        navigate(workspaceRootPath(ws.id));
                      }}
                      type="button"
                    >
                      <span className="workspace-name">{ws.name}</span>
                      <span className={`role-badge role-${roleName}`}>{roleText(ws.role)}</span>
                      {isSelected ? <span className="workspace-current">사용 중</span> : null}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      </Layout>
    );
  }

  function InboxPage() {
    const [documents, setDocuments] = useState<DocumentItem[]>([]);
    const [error, setError] = useState<UiError | null>(null);
    const [viewMode, setViewMode] = useState<"CARD" | "LIST">("CARD");
    const [sortBy, setSortBy] = useState<"UPDATED" | "TITLE">("UPDATED");
    const [bulkRetrying, setBulkRetrying] = useState(false);
    const [bulkRetryError, setBulkRetryError] = useState<UiError | null>(null);
    const [bulkRetryNotice, setBulkRetryNotice] = useState<UiNotice | null>(null);

    const loadDocuments = useCallback(async () => {
      setError(null);
      try {
        const response = await api.request<DocumentListResponse>("/documents?page=0&size=20", {}, true);
        setDocuments(response.items);
      } catch (e) {
        setError(toUiError(e, "문서 목록을 불러오지 못했습니다"));
      }
    }, [api]);

    useEffect(() => {
      if (!state.workspaceId) {
        setDocuments([]);
        return;
      }
      void loadDocuments();
    }, [loadDocuments, state.workspaceId]);

    const sortedDocuments = useMemo(() => {
      const ordered = [...documents];
      if (sortBy === "TITLE") {
        ordered.sort((left, right) => left.title.localeCompare(right.title, "ko"));
        return ordered;
      }
      ordered.sort(sortDocumentsByRecency);
      return ordered;
    }, [documents, sortBy]);

    const failedRetryTargets = useMemo(
      () =>
        documents
          .map((doc) => ({ doc, failedStage: getFailedPipelineStage(doc) }))
          .filter((entry): entry is { doc: DocumentItem; failedStage: PipelineStage } => entry.failedStage !== null),
      [documents]
    );

    useEffect(() => {
      setBulkRetryNotice(null);
      setBulkRetryError(null);
      setBulkRetrying(false);
    }, [state.workspaceId]);

    const retryFailedDocuments = useCallback(async () => {
      if (!state.workspaceId || bulkRetrying) {
        return;
      }
      if (failedRetryTargets.length === 0) {
        setBulkRetryNotice({ tone: "info", message: "재실행할 실패 문서가 없습니다." });
        setBulkRetryError(null);
        return;
      }

      setBulkRetrying(true);
      setBulkRetryError(null);
      setBulkRetryNotice(null);

      try {
        const results = await Promise.allSettled(
          failedRetryTargets.map((target) =>
            api.request(
              `/documents/${target.doc.id}/pipeline/retry`,
              {
                method: "POST",
                body: JSON.stringify({ stage: target.failedStage })
              },
              true
            )
          )
        );

        const successCount = results.filter((result) => result.status === "fulfilled").length;
        const failedCount = results.length - successCount;

        if (successCount > 0) {
          setBulkRetryNotice({
            tone: failedCount === 0 ? "success" : "info",
            message:
              failedCount === 0
                ? `실패 문서 ${successCount}건의 재실행을 요청했습니다.`
                : `실패 문서 ${successCount}건 재실행 요청 완료, ${failedCount}건은 실패했습니다.`
          });
        } else {
          setBulkRetryNotice(null);
        }

        if (failedCount > 0) {
          setBulkRetryError({
            message: `${failedCount}건은 재실행 요청에 실패했습니다. 잠시 후 다시 시도하세요.`,
            status: null
          });
        }

        await loadDocuments();
      } finally {
        setBulkRetrying(false);
      }
    }, [api, bulkRetrying, failedRetryTargets, loadDocuments, state.workspaceId]);

    return (
      <Layout api={api}>
        <section className="panel">
          <PageHeader
            title="Documents"
            subtitle="문서 데이터베이스를 카드/리스트 뷰로 탐색하고 바로 편집으로 이동하세요."
            action={
              <div className="action-row">
                <button
                  className="btn btn-primary btn-small"
                  disabled={!state.workspaceId || bulkRetrying || failedRetryTargets.length === 0}
                  onClick={() => {
                    void retryFailedDocuments();
                  }}
                  type="button"
                >
                  {bulkRetrying
                    ? "실패 항목 전체 재실행 중..."
                    : `실패 항목 전체 재실행${failedRetryTargets.length > 0 ? ` (${failedRetryTargets.length})` : ""}`}
                </button>
                <div className="editor-view-toggle" role="tablist" aria-label="문서함 보기 전환">
                  <button
                    aria-selected={viewMode === "CARD"}
                    className={`editor-view-toggle-button${viewMode === "CARD" ? " is-active" : ""}`}
                    onClick={() => setViewMode("CARD")}
                    role="tab"
                    type="button"
                  >
                    카드
                  </button>
                  <button
                    aria-selected={viewMode === "LIST"}
                    className={`editor-view-toggle-button${viewMode === "LIST" ? " is-active" : ""}`}
                    onClick={() => setViewMode("LIST")}
                    role="tab"
                    type="button"
                  >
                    리스트
                  </button>
                </div>
                <label className="view-selector" htmlFor="documents-sort-select">
                  <span>정렬</span>
                  <select
                    className="field-input"
                    id="documents-sort-select"
                    onChange={(event) => setSortBy(event.target.value as "UPDATED" | "TITLE")}
                    value={sortBy}
                  >
                    <option value="UPDATED">최근 수정</option>
                    <option value="TITLE">제목</option>
                  </select>
                </label>
              </div>
            }
          />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadDocuments();
            }}
          />
          <NoticePanel notice={bulkRetryNotice} />
          <ErrorPanel
            error={bulkRetryError}
            onRetry={() => {
              void retryFailedDocuments();
            }}
          />

          {state.workspaceId && documents.length === 0 ? (
            <EmptyState title="문서가 없습니다" description="에디터 탭에서 문서를 작성하거나 업로드하세요." />
          ) : null}

          {viewMode === "CARD" ? (
            <div className="doc-grid">
              {sortedDocuments.map((doc) => (
                <article className="panel panel-soft doc-card" key={doc.id}>
                  <div className="doc-card-header">
                    <Link className="doc-link" to={state.workspaceId ? workspaceDocumentPath(state.workspaceId, doc.id) : `/documents/${doc.id}`}>
                      {doc.title}
                    </Link>
                    <StatusChip label="상태" value={doc.status} />
                  </div>
                  <div className="pipeline-grid">
                    <StatusChip label="수집" value={doc.pipeline_status.ingest} />
                    <StatusChip label="임베딩" value={doc.pipeline_status.embed} />
                    <StatusChip label="인덱스" value={doc.pipeline_status.index} />
                    <StatusChip label="트리" value={doc.pipeline_status.tree} />
                  </div>
                  <div className="action-row">
                    <Link className="btn btn-secondary btn-small" to={state.workspaceId ? workspaceDocumentPath(state.workspaceId, doc.id) : `/documents/${doc.id}`}>
                      열기
                    </Link>
                    <Link
                      className="btn btn-ghost btn-small"
                      to={state.workspaceId ? workspaceDocumentDetailPath(state.workspaceId, doc.id) : `/documents/${doc.id}`}
                    >
                      상세
                    </Link>
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <ul className="documents-list-view">
              {sortedDocuments.map((doc) => (
                <li className="documents-list-row" key={doc.id}>
                  <div className="documents-list-main">
                    <Link className="doc-link" to={state.workspaceId ? workspaceDocumentPath(state.workspaceId, doc.id) : `/documents/${doc.id}`}>
                      {doc.title}
                    </Link>
                    <p className="muted">마지막 수정 {new Date(doc.updated_at).toLocaleString("ko-KR")}</p>
                  </div>
                  <div className="pipeline-grid">
                    <StatusChip label="수집" value={doc.pipeline_status.ingest} />
                    <StatusChip label="임베딩" value={doc.pipeline_status.embed} />
                    <StatusChip label="인덱스" value={doc.pipeline_status.index} />
                    <StatusChip label="트리" value={doc.pipeline_status.tree} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </Layout>
    );
  }

  function EditorPage() {
    const [documents, setDocuments] = useState<DocumentItem[]>([]);
    const [sidebarState, setSidebarState] = useState<EditorSidebarWorkspaceState>(emptyEditorSidebarState);
    const [sidebarMode, setSidebarMode] = useState<EditorSidebarMode>("DOCUMENT");
    const [selectedDocumentId, setSelectedDocumentId] = useState<string | null>(null);
    const [selectedDocument, setSelectedDocument] = useState<DocumentItem | null>(null);
    const [draftTitle, setDraftTitle] = useState("");
    const [draftBody, setDraftBody] = useState("");
    const [searchQuery, setSearchQuery] = useState("");
    const [collapsedIds, setCollapsedIds] = useState<string[]>([]);
    const [collapsedNodeIds, setCollapsedNodeIds] = useState<string[]>([]);
    const [openMenuDocId, setOpenMenuDocId] = useState<string | null>(null);
    const [renamingDocumentId, setRenamingDocumentId] = useState<string | null>(null);
    const [renameDraft, setRenameDraft] = useState("");
    const [renamePendingDocumentId, setRenamePendingDocumentId] = useState<string | null>(null);
    const [loadingList, setLoadingList] = useState(false);
    const [documentsLoaded, setDocumentsLoaded] = useState(false);
    const [loadingNodeTree, setLoadingNodeTree] = useState(false);
    const [loadingDocument, setLoadingDocument] = useState(false);
    const [saving, setSaving] = useState(false);
    const [creatingParentId, setCreatingParentId] = useState<string | null>(null);
    const [deletingDocumentId, setDeletingDocumentId] = useState<string | null>(null);
    const [nodeTreeResponse, setNodeTreeResponse] = useState<TreeActiveResponse | null>(null);
    const [nodeTreeError, setNodeTreeError] = useState<UiError | null>(null);
    const [error, setError] = useState<UiError | null>(null);
    const [notice, setNotice] = useState<UiNotice | null>(null);
    const selectedRequestSequence = useRef(0);
    const ROOT_CREATE_ID = "__root__";

    const loadDocuments = useCallback(async () => {
      if (!state.workspaceId) {
        setDocuments([]);
        setDocumentsLoaded(false);
        return;
      }
      setLoadingList(true);
      setError(null);
      try {
        const response = await api.request<DocumentListResponse>("/documents?page=0&size=200", {}, true);
        setDocuments(response.items);
        setDocumentsLoaded(true);
      } catch (e) {
        setError(toUiError(e, "문서 목록을 불러오지 못했습니다"));
      } finally {
        setLoadingList(false);
      }
    }, [api, state.workspaceId]);

    const loadNodeTree = useCallback(async () => {
      if (!state.workspaceId) {
        setNodeTreeResponse(null);
        setNodeTreeError(null);
        return;
      }
      setLoadingNodeTree(true);
      setNodeTreeError(null);
      try {
        const response = await api.request<TreeActiveResponse>("/trees?view=topic", {}, true);
        setNodeTreeResponse(response);
      } catch (e) {
        setNodeTreeError(toUiError(e, "노드 분류 트리를 불러오지 못했습니다"));
      } finally {
        setLoadingNodeTree(false);
      }
    }, [api, state.workspaceId]);

    useEffect(() => {
      setSidebarState(loadEditorSidebarState(state.workspaceId));
      setCollapsedIds([]);
      setCollapsedNodeIds([]);
      setSidebarMode("DOCUMENT");
      setOpenMenuDocId(null);
      setRenamingDocumentId(null);
      setRenameDraft("");
      setRenamePendingDocumentId(null);
      setDocumentsLoaded(false);
      setNodeTreeResponse(null);
      setNodeTreeError(null);
    }, [state.workspaceId]);

    useEffect(() => {
      saveEditorSidebarState(state.workspaceId, sidebarState);
    }, [sidebarState, state.workspaceId]);

    useEffect(() => {
      if (!state.workspaceId) {
        setDocuments([]);
        setDocumentsLoaded(false);
        setSelectedDocumentId(null);
        setSelectedDocument(null);
        setDraftTitle("");
        setDraftBody("");
        return;
      }
      setDocumentsLoaded(false);
      void loadDocuments();
    }, [loadDocuments, state.workspaceId]);

    useEffect(() => {
      if (!state.workspaceId) {
        return;
      }
      if (sidebarMode !== "NODE") {
        return;
      }
      void loadNodeTree();
    }, [loadNodeTree, sidebarMode, state.workspaceId]);

    useEffect(() => {
      if (!documentsLoaded) {
        return;
      }
      setSidebarState((previous) => {
        const sanitized = sanitizeEditorSidebarState(previous, documents);
        return isEditorSidebarStateEqual(previous, sanitized) ? previous : sanitized;
      });
    }, [documents, documentsLoaded]);

    const favoriteSet = useMemo(() => new Set(sidebarState.favorites), [sidebarState.favorites]);
    const collapsedSet = useMemo(() => new Set(collapsedIds), [collapsedIds]);
    const collapsedNodeSet = useMemo(() => new Set(collapsedNodeIds), [collapsedNodeIds]);
    const documentById = useMemo(() => new Map(documents.map((document) => [document.id, document])), [documents]);

    const orderedDocuments = useMemo(() => {
      return [...documents].sort((left, right) => {
        const favoriteDelta = Number(favoriteSet.has(right.id)) - Number(favoriteSet.has(left.id));
        if (favoriteDelta !== 0) {
          return favoriteDelta;
        }
        const leftUpdated = Date.parse(left.updated_at);
        const rightUpdated = Date.parse(right.updated_at);
        const safeLeftUpdated = Number.isFinite(leftUpdated) ? leftUpdated : 0;
        const safeRightUpdated = Number.isFinite(rightUpdated) ? rightUpdated : 0;
        if (safeRightUpdated !== safeLeftUpdated) {
          return safeRightUpdated - safeLeftUpdated;
        }
        return left.title.localeCompare(right.title, "ko");
      });
    }, [documents, favoriteSet]);

    const treeRoots = useMemo<EditorTreeNode[]>(() => {
      const nodeById = new Map<string, EditorTreeNode>();
      for (const document of orderedDocuments) {
        nodeById.set(document.id, { doc: document, children: [] });
      }
      const roots: EditorTreeNode[] = [];
      for (const document of orderedDocuments) {
        const node = nodeById.get(document.id);
        if (!node) {
          continue;
        }
        const parentId = document.parent_document_id ?? sidebarState.parents[document.id];
        const parentNode = parentId ? nodeById.get(parentId) : undefined;
        if (parentNode && parentId !== document.id) {
          parentNode.children.push(node);
        } else {
          roots.push(node);
        }
      }
      return roots;
    }, [orderedDocuments, sidebarState.parents]);

    const orderedDocumentIds = useMemo(() => {
      const result: string[] = [];
      const traverse = (nodes: EditorTreeNode[]) => {
        for (const node of nodes) {
          result.push(node.doc.id);
          if (node.children.length > 0) {
            traverse(node.children);
          }
        }
      };
      traverse(treeRoots);
      return result;
    }, [treeRoots]);

    const nodeTrees = useMemo<EditorNodeTree[]>(() => {
      if (!nodeTreeResponse) {
        return [];
      }

      const nodes = nodeTreeResponse.nodes;
      const grouped = new Map<
        string,
        {
          id: string;
          parentId: string | null;
          label: string;
          locked: boolean;
          documents: Array<{ id: string; title: string }>;
          children: EditorNodeTree[];
        }
      >();

      for (const node of nodes) {
        const summaryTitleById = new Map((node.document_summaries ?? []).map((summary) => [summary.id, summary.title?.trim() ?? ""]));
        const nodeDocumentIds = Array.from(new Set([...(node.documents ?? []), ...(node.document_summaries ?? []).map((summary) => summary.id)]));
        grouped.set(node.id, {
          id: node.id,
          parentId: node.parent_id,
          label: node.label,
          locked: node.locked,
          documents: nodeDocumentIds
            .map((documentId) => ({
              id: documentId,
              title: summaryTitleById.get(documentId) || documentById.get(documentId)?.title || documentId
            }))
            .sort((left, right) => left.title.localeCompare(right.title, "ko")),
          children: []
        });
      }

      const roots: EditorNodeTree[] = [];
      for (const value of grouped.values()) {
        const node: EditorNodeTree = {
          id: value.id,
          label: value.label,
          locked: value.locked,
          documents: value.documents,
          children: value.children
        };
        const parent = value.parentId ? grouped.get(value.parentId) : undefined;
        if (parent) {
          parent.children.push(node);
        } else {
          roots.push(node);
        }
      }

      const sortNodes = (list: EditorNodeTree[]) => {
        list.sort((left, right) => left.label.localeCompare(right.label, "ko"));
        for (const item of list) {
          if (item.children.length > 0) {
            sortNodes(item.children);
          }
        }
      };
      sortNodes(roots);
      return roots;
    }, [documentById, nodeTreeResponse]);

    const nodeModeDocumentIds = useMemo(() => {
      const result: string[] = [];
      const seen = new Set<string>();

      const traverse = (nodes: EditorNodeTree[]) => {
        for (const node of nodes) {
          for (const document of node.documents) {
            if (!seen.has(document.id)) {
              seen.add(document.id);
              result.push(document.id);
            }
          }
          if (node.children.length > 0) {
            traverse(node.children);
          }
        }
      };

      traverse(nodeTrees);
      return result;
    }, [nodeTrees]);

    const activeSelectableDocumentIds = useMemo(
      () => (sidebarMode === "NODE" ? nodeModeDocumentIds : orderedDocumentIds),
      [nodeModeDocumentIds, orderedDocumentIds, sidebarMode]
    );

    useEffect(() => {
      if (!state.workspaceId) {
        setSelectedDocumentId(null);
        return;
      }
      if (activeSelectableDocumentIds.length === 0) {
        setSelectedDocumentId(null);
        return;
      }
      if (selectedDocumentId && activeSelectableDocumentIds.includes(selectedDocumentId)) {
        return;
      }
      setSelectedDocumentId(activeSelectableDocumentIds[0]);
    }, [activeSelectableDocumentIds, selectedDocumentId, state.workspaceId]);

    useEffect(() => {
      if (!state.workspaceId || !selectedDocumentId) {
        setSelectedDocument(null);
        setDraftTitle("");
        setDraftBody("");
        return;
      }
      let active = true;
      const sequence = selectedRequestSequence.current + 1;
      selectedRequestSequence.current = sequence;
      setLoadingDocument(true);
      setError(null);

      void (async () => {
        try {
          const payload = await api.request<DocumentItem>(`/documents/${selectedDocumentId}`, {}, true);
          if (!active || selectedRequestSequence.current !== sequence) {
            return;
          }
          setSelectedDocument(payload);
          setDraftTitle(payload.title);
          setDraftBody(payload.body_markdown ?? "");
        } catch (e) {
          if (!active || selectedRequestSequence.current !== sequence) {
            return;
          }
          setError(toUiError(e, "문서를 불러오지 못했습니다"));
        } finally {
          if (active && selectedRequestSequence.current === sequence) {
            setLoadingDocument(false);
          }
        }
      })();

      return () => {
        active = false;
      };
    }, [api, selectedDocumentId, state.workspaceId]);

    useEffect(() => {
      if (!openMenuDocId) {
        return;
      }

      const closeOnOutsideClick = (event: MouseEvent) => {
        let cursor = event.target as HTMLElement | null;
        while (cursor) {
          if (cursor.dataset.editorMenuRoot === openMenuDocId) {
            return;
          }
          cursor = cursor.parentElement;
        }
        setOpenMenuDocId(null);
      };

      const closeOnEscape = (event: KeyboardEvent) => {
        if (event.key === "Escape") {
          setOpenMenuDocId(null);
        }
      };

      window.addEventListener("mousedown", closeOnOutsideClick);
      window.addEventListener("keydown", closeOnEscape);
      return () => {
        window.removeEventListener("mousedown", closeOnOutsideClick);
        window.removeEventListener("keydown", closeOnEscape);
      };
    }, [openMenuDocId]);

    useEffect(() => {
      if (renamingDocumentId && renamingDocumentId !== openMenuDocId) {
        setRenamingDocumentId(null);
        setRenameDraft("");
        setRenamePendingDocumentId(null);
      }
    }, [openMenuDocId, renamingDocumentId]);

    const toggleCollapsed = useCallback((documentId: string) => {
      setCollapsedIds((previous) =>
        previous.includes(documentId) ? previous.filter((item) => item !== documentId) : [...previous, documentId]
      );
    }, []);

    const toggleNodeCollapsed = useCallback((nodeId: string) => {
      setCollapsedNodeIds((previous) => (previous.includes(nodeId) ? previous.filter((item) => item !== nodeId) : [...previous, nodeId]));
    }, []);

    const toggleFavorite = useCallback((documentId: string) => {
      setSidebarState((previous) => {
        const favorites = new Set(previous.favorites);
        if (favorites.has(documentId)) {
          favorites.delete(documentId);
        } else {
          favorites.add(documentId);
        }
        return normalizeEditorSidebarState({ ...previous, favorites: Array.from(favorites) });
      });
    }, []);

    const createDocument = useCallback(
      async (parentId: string | null) => {
        if (!state.workspaceId) {
          return;
        }
        setCreatingParentId(parentId ?? ROOT_CREATE_ID);
        setError(null);
        setNotice(null);
        try {
          const parentTitle = parentId ? documentById.get(parentId)?.title?.trim() : "";
          const newTitle = parentTitle ? `${parentTitle} 하위 페이지` : "새 페이지";
          const created = await api.request<{ id: string }>(
            "/documents",
            {
              method: "POST",
                  body: JSON.stringify({
                    title: newTitle,
                    body_markdown: "",
                    source_type: "EDITOR",
                    parent_document_id: parentId
                  })
                },
                true
              );

          setSidebarState((previous) => {
            const parents = { ...previous.parents };
            if (parentId) {
              parents[created.id] = parentId;
            } else {
              delete parents[created.id];
            }
            return normalizeEditorSidebarState({ ...previous, parents });
          });

          if (parentId) {
            setCollapsedIds((previous) => previous.filter((value) => value !== parentId));
          }
          setOpenMenuDocId(null);
          await loadDocuments();
          setSelectedDocumentId(created.id);
          setNotice({
            tone: "success",
            message: parentId ? "하위 페이지를 생성했습니다." : "새 페이지를 생성했습니다."
          });
        } catch (e) {
          setError(toUiError(e, "문서 생성에 실패했습니다"));
        } finally {
          setCreatingParentId(null);
        }
      },
      [ROOT_CREATE_ID, api, documentById, loadDocuments, state.workspaceId]
    );

    const saveSelectedDocument = useCallback(async () => {
      if (!selectedDocumentId || !selectedDocument || !state.workspaceId) {
        return;
      }
      if (typeof selectedDocument.version !== "number") {
        setError({ message: "문서 버전을 확인할 수 없습니다. 다시 열어주세요.", status: null });
        return;
      }

      const normalizedTitle = draftTitle.trim() || "제목 없음";
      setSaving(true);
      setError(null);
      setNotice(null);
      try {
        await api.request<void>(
          `/documents/${selectedDocumentId}`,
          {
            method: "PATCH",
            body: JSON.stringify({
              version: selectedDocument.version,
              title: normalizedTitle,
              body_markdown: draftBody
            })
          },
          true
        );

        const refreshed = await api.request<DocumentItem>(`/documents/${selectedDocumentId}`, {}, true);
        setSelectedDocument(refreshed);
        setDraftTitle(refreshed.title);
        setDraftBody(refreshed.body_markdown ?? "");
        setDocuments((previous) =>
          previous.map((document) =>
            document.id === refreshed.id
              ? {
                  ...document,
                  title: refreshed.title,
                  status: refreshed.status,
                  pipeline_status: refreshed.pipeline_status,
                  attachments: refreshed.attachments,
                  parent_document_id: refreshed.parent_document_id,
                  updated_at: refreshed.updated_at,
                  version: refreshed.version
                }
              : document
          )
        );
        setNotice({ tone: "success", message: "문서를 저장했습니다." });
      } catch (e) {
        setError(toUiError(e, "문서 저장에 실패했습니다"));
      } finally {
        setSaving(false);
      }
    }, [api, draftBody, draftTitle, selectedDocument, selectedDocumentId, state.workspaceId]);

    const beginRenameDocument = useCallback((document: DocumentItem) => {
      setError(null);
      setNotice(null);
      setOpenMenuDocId(document.id);
      setRenamingDocumentId(document.id);
      setRenameDraft(document.title);
    }, []);

    const cancelRenameDocument = useCallback(() => {
      if (renamePendingDocumentId) {
        return;
      }
      setRenamingDocumentId(null);
      setRenameDraft("");
    }, [renamePendingDocumentId]);

    const renameDocument = useCallback(
      async (documentId: string) => {
        if (!state.workspaceId || renamePendingDocumentId) {
          return;
        }
        const target = documentById.get(documentId);
        if (!target) {
          return;
        }
        const nextTitle = renameDraft.trim();
        if (!nextTitle || nextTitle === target.title) {
          setRenamingDocumentId(null);
          setRenameDraft("");
          return;
        }

        setError(null);
        setNotice(null);
        setRenamePendingDocumentId(documentId);
        try {
          let version: number | undefined;
          let bodyMarkdown = "";
          if (selectedDocumentId === documentId && selectedDocument) {
            version = selectedDocument.version;
            bodyMarkdown = draftBody;
          } else {
            const current = await api.request<DocumentItem>(`/documents/${documentId}`, {}, true);
            version = current.version;
            bodyMarkdown = current.body_markdown ?? "";
          }
          if (typeof version !== "number") {
            throw new Error("document_version_missing");
          }

          await api.request<void>(
            `/documents/${documentId}`,
            {
              method: "PATCH",
              body: JSON.stringify({
                version,
                title: nextTitle,
                body_markdown: bodyMarkdown
              })
            },
            true
          );

          await loadDocuments();
          if (selectedDocumentId === documentId) {
            const refreshed = await api.request<DocumentItem>(`/documents/${documentId}`, {}, true);
            setSelectedDocument(refreshed);
            setDraftTitle(refreshed.title);
            setDraftBody(refreshed.body_markdown ?? "");
          }
          setOpenMenuDocId(null);
          setRenamingDocumentId(null);
          setRenameDraft("");
          setNotice({ tone: "success", message: "페이지 이름을 변경했습니다." });
        } catch (e) {
          setError(toUiError(e, "페이지 이름 변경에 실패했습니다"));
        } finally {
          setRenamePendingDocumentId(null);
        }
      },
      [
        api,
        documentById,
        draftBody,
        loadDocuments,
        renameDraft,
        renamePendingDocumentId,
        selectedDocument,
        selectedDocumentId,
        state.workspaceId
      ]
    );

    const deleteDocument = useCallback(
      async (documentId: string) => {
        if (!state.workspaceId) {
          return;
        }
        const target = documentById.get(documentId);
        if (!target) {
          return;
        }

        const confirmed = window.confirm(`"${target.title}" 페이지를 삭제하시겠습니까?`);
        if (!confirmed) {
          return;
        }

        setDeletingDocumentId(documentId);
        setOpenMenuDocId(null);
        setError(null);
        setNotice(null);

        try {
          await api.request<void>(
            `/documents/${documentId}`,
            {
              method: "DELETE"
            },
            true
          );

          setSidebarState((previous) => {
            const parents = { ...previous.parents };
            const deletedParentId = parents[documentId] ?? null;
            delete parents[documentId];
            for (const [childId, parentId] of Object.entries(parents)) {
              if (parentId === documentId) {
                parents[childId] = deletedParentId;
              }
            }
            return normalizeEditorSidebarState({
              parents,
              favorites: previous.favorites.filter((favoriteId) => favoriteId !== documentId)
            });
          });

          if (selectedDocumentId === documentId) {
            setSelectedDocumentId(null);
            setSelectedDocument(null);
            setDraftTitle("");
            setDraftBody("");
          }
          setDocuments((previous) => previous.filter((document) => document.id !== documentId));
          await loadDocuments();
          setNotice({ tone: "success", message: "페이지를 삭제했습니다." });
        } catch (e) {
          setError(toUiError(e, "페이지 삭제에 실패했습니다"));
        } finally {
          setDeletingDocumentId(null);
        }
      },
      [api, documentById, loadDocuments, selectedDocumentId, state.workspaceId]
    );

    const copyDocumentLink = useCallback(async (documentId: string) => {
      setOpenMenuDocId(null);
      setError(null);
      try {
        const copiedUrl = state.workspaceId
          ? `${window.location.origin}${workspaceDocumentPath(state.workspaceId, documentId)}`
          : `${window.location.origin}/documents/${documentId}`;
        await copyTextToClipboard(copiedUrl);
        setNotice({ tone: "info", message: "문서 링크를 복사했습니다." });
      } catch (e) {
        setError(toUiError(e, "링크 복사에 실패했습니다"));
      }
    }, [state.workspaceId]);

    useEffect(() => {
      const handleSaveShortcut = (event: KeyboardEvent) => {
        if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "s") {
          return;
        }
        if (!selectedDocumentId || saving) {
          return;
        }
        event.preventDefault();
        void saveSelectedDocument();
      };
      window.addEventListener("keydown", handleSaveShortcut);
      return () => {
        window.removeEventListener("keydown", handleSaveShortcut);
      };
    }, [saveSelectedDocument, saving, selectedDocumentId]);

    const filteredTreeRoots = useMemo(() => {
      const normalized = searchQuery.trim().toLowerCase();
      if (!normalized) {
        return treeRoots;
      }
      const filterTree = (node: EditorTreeNode): EditorTreeNode | null => {
        const filteredChildren = node.children
          .map((child) => filterTree(child))
          .filter((child): child is EditorTreeNode => child !== null);
        const matched = node.doc.title.toLowerCase().includes(normalized);
        if (!matched && filteredChildren.length === 0) {
          return null;
        }
        return {
          doc: node.doc,
          children: filteredChildren
        };
      };
      return treeRoots
        .map((node) => filterTree(node))
        .filter((node): node is EditorTreeNode => node !== null);
    }, [searchQuery, treeRoots]);

    const filteredNodeTrees = useMemo(() => {
      const normalized = searchQuery.trim().toLowerCase();
      if (!normalized) {
        return nodeTrees;
      }

      const filterNode = (node: EditorNodeTree): EditorNodeTree | null => {
        const filteredChildren = node.children
          .map((child) => filterNode(child))
          .filter((child): child is EditorNodeTree => child !== null);
        const filteredDocuments = node.documents.filter((document) => document.title.toLowerCase().includes(normalized));
        const matchedLabel = node.label.toLowerCase().includes(normalized);
        if (!matchedLabel && filteredChildren.length === 0 && filteredDocuments.length === 0) {
          return null;
        }
        return {
          ...node,
          children: filteredChildren,
          documents: matchedLabel ? node.documents : filteredDocuments
        };
      };

      return nodeTrees.map((node) => filterNode(node)).filter((node): node is EditorNodeTree => node !== null);
    }, [nodeTrees, searchQuery]);

    const selectedNodeLabel = useMemo(() => {
      if (!selectedDocumentId || !nodeTreeResponse) {
        return null;
      }
      for (const node of nodeTreeResponse.nodes) {
        if (node.documents.includes(selectedDocumentId) || (node.document_summaries ?? []).some((summary) => summary.id === selectedDocumentId)) {
          return node.label;
        }
      }
      return null;
    }, [nodeTreeResponse, selectedDocumentId]);

    const selectedParentTitle = useMemo(() => {
      if (sidebarMode === "NODE") {
        return selectedNodeLabel ? `노드: ${selectedNodeLabel}` : "노드 미분류";
      }
      if (!selectedDocumentId) {
        return null;
      }
      const document = documentById.get(selectedDocumentId);
      const parentId = document?.parent_document_id ?? sidebarState.parents[selectedDocumentId];
      if (!parentId) {
        return "상위: 루트 페이지";
      }
      return `상위: ${documentById.get(parentId)?.title ?? "루트 페이지"}`;
    }, [documentById, selectedDocumentId, selectedNodeLabel, sidebarMode, sidebarState.parents]);

    const isDirty =
      selectedDocument !== null &&
      (draftTitle.trim() !== selectedDocument.title.trim() || draftBody !== (selectedDocument.body_markdown ?? ""));

    const renderTree = (nodes: EditorTreeNode[], depth: number): React.ReactNode =>
      nodes.map((node) => {
        const hasChildren = node.children.length > 0;
        const isCollapsed = !searchQuery.trim() && collapsedSet.has(node.doc.id);
        const isSelected = selectedDocumentId === node.doc.id;
        const isFavorite = favoriteSet.has(node.doc.id);
        const isCreatingChild = creatingParentId === node.doc.id;
        const isDeleting = deletingDocumentId === node.doc.id;
        const isRenaming = renamingDocumentId === node.doc.id;
        const isRenamePending = renamePendingDocumentId === node.doc.id;

        return (
          <li className="editor-tree-item" key={node.doc.id}>
            <div className={`editor-tree-row${isSelected ? " is-selected" : ""}`} data-editor-menu-root={node.doc.id}>
              <button
                aria-label={hasChildren ? "하위 문서 펼치기/접기" : "하위 문서 없음"}
                className={`editor-tree-toggle${hasChildren ? "" : " is-placeholder"}`}
                disabled={!hasChildren}
                onClick={() => {
                  if (hasChildren) {
                    toggleCollapsed(node.doc.id);
                  }
                }}
                type="button"
              >
                {hasChildren ? (isCollapsed ? "▶" : "▼") : "•"}
              </button>

              <button
                className="editor-tree-main"
                onClick={() => {
                  setSelectedDocumentId(node.doc.id);
                  setOpenMenuDocId(null);
                }}
                style={{ paddingLeft: `${depth * 16}px` }}
                type="button"
              >
                <span className="editor-tree-title">{node.doc.title}</span>
                {isFavorite ? <span className="editor-tree-favorite" title="즐겨찾기">★</span> : null}
              </button>

              <div className="editor-tree-row-actions">
                <button
                  aria-label="하위 페이지 추가"
                  className="editor-tree-action"
                  disabled={isCreatingChild || isDeleting}
                  onClick={() => {
                    void createDocument(node.doc.id);
                  }}
                  type="button"
                >
                  +
                </button>
                <button
                  aria-expanded={openMenuDocId === node.doc.id}
                  aria-label="페이지 메뉴"
                  className="editor-tree-action"
                  onClick={() => {
                    setOpenMenuDocId((previous) => {
                      const next = previous === node.doc.id ? null : node.doc.id;
                      if (next !== node.doc.id) {
                        setRenamingDocumentId(null);
                        setRenameDraft("");
                      }
                      return next;
                    });
                  }}
                  type="button"
                >
                  ...
                </button>
                {openMenuDocId === node.doc.id ? (
                  <div className="editor-tree-menu" role="menu">
                    <button
                      className="editor-tree-menu-item"
                      onClick={() => {
                        toggleFavorite(node.doc.id);
                        setOpenMenuDocId(null);
                      }}
                      role="menuitem"
                      type="button"
                    >
                      {isFavorite ? "즐겨찾기 해제" : "즐겨찾기에 추가"}
                    </button>
                    <button
                      className="editor-tree-menu-item"
                      onClick={() => {
                        void copyDocumentLink(node.doc.id);
                      }}
                      role="menuitem"
                      type="button"
                    >
                      링크 복사
                    </button>
                    {isRenaming ? (
                      <div className="editor-tree-inline-rename">
                        <input
                          aria-label="새 페이지 이름"
                          className="field-input inline-rename-input"
                          onChange={(event) => setRenameDraft(event.target.value)}
                          onKeyDown={(event) => {
                            if (event.key === "Enter") {
                              event.preventDefault();
                              void renameDocument(node.doc.id);
                            } else if (event.key === "Escape") {
                              event.preventDefault();
                              cancelRenameDocument();
                            }
                          }}
                          value={renameDraft}
                        />
                        <div className="inline-rename-actions">
                          <button
                            className="btn btn-ghost btn-small inline-rename-button"
                            disabled={isRenamePending}
                            onClick={() => {
                              cancelRenameDocument();
                            }}
                            type="button"
                          >
                            취소
                          </button>
                          <button
                            className="btn btn-primary btn-small inline-rename-button"
                            disabled={isRenamePending || !renameDraft.trim()}
                            onClick={() => {
                              void renameDocument(node.doc.id);
                            }}
                            type="button"
                          >
                            {isRenamePending ? "저장 중..." : "저장"}
                          </button>
                        </div>
                      </div>
                    ) : (
                      <button
                        className="editor-tree-menu-item"
                        onClick={() => {
                          beginRenameDocument(node.doc);
                        }}
                        role="menuitem"
                        type="button"
                      >
                        이름 바꾸기
                      </button>
                    )}
                    <button
                      className="editor-tree-menu-item is-danger"
                      disabled={isDeleting || isRenamePending}
                      onClick={() => {
                        void deleteDocument(node.doc.id);
                      }}
                      role="menuitem"
                      type="button"
                    >
                      휴지통으로 이동
                    </button>
                  </div>
                ) : null}
              </div>
            </div>

            {hasChildren && !isCollapsed ? <ul className="editor-tree-list">{renderTree(node.children, depth + 1)}</ul> : null}
          </li>
        );
      });

    const countNodeDocuments = useCallback((node: EditorNodeTree): number => {
      return node.documents.length + node.children.reduce((acc, child) => acc + countNodeDocuments(child), 0);
    }, []);

    const renderNodeTree = (nodes: EditorNodeTree[], depth: number): React.ReactNode =>
      nodes.map((node) => {
        const hasChildren = node.children.length > 0;
        const hasDocuments = node.documents.length > 0;
        const canToggle = hasChildren || hasDocuments;
        const isCollapsed = !searchQuery.trim() && collapsedNodeSet.has(node.id);
        const totalDocuments = countNodeDocuments(node);

        return (
          <li className="editor-node-item" key={node.id}>
            <div className="editor-node-row">
              <button
                aria-label={canToggle ? "노드 펼치기/접기" : "하위 항목 없음"}
                className={`editor-tree-toggle${canToggle ? "" : " is-placeholder"}`}
                disabled={!canToggle}
                onClick={() => {
                  if (canToggle) {
                    toggleNodeCollapsed(node.id);
                  }
                }}
                type="button"
              >
                {canToggle ? (isCollapsed ? "▶" : "▼") : "•"}
              </button>

              <button
                className="editor-node-label"
                onClick={() => {
                  if (canToggle) {
                    toggleNodeCollapsed(node.id);
                  }
                }}
                style={{ paddingLeft: `${depth * 16}px` }}
                type="button"
              >
                <span>{node.label}</span>
                <span className="editor-node-meta">
                  {node.locked ? <span className="lock-badge">잠금</span> : null}
                  <span className="tree-node-count">{totalDocuments}</span>
                </span>
              </button>
            </div>

            {!isCollapsed ? (
              <>
                {hasDocuments ? (
                  <ul className="editor-node-doc-list">
                    {node.documents.map((document) => (
                      <li key={`${node.id}:${document.id}`}>
                        <button
                          className={`editor-node-doc-button${selectedDocumentId === document.id ? " is-selected" : ""}`}
                          onClick={() => {
                            setSelectedDocumentId(document.id);
                            setOpenMenuDocId(null);
                          }}
                          style={{ marginLeft: `${(depth + 1) * 16}px` }}
                          type="button"
                        >
                          {document.title}
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : null}
                {hasChildren ? <ul className="editor-node-list">{renderNodeTree(node.children, depth + 1)}</ul> : null}
              </>
            ) : null}
          </li>
        );
      });

    return (
      <Layout api={api}>
        <section className="panel">
          <PageHeader title="에디터" subtitle="문서를 트리로 관리하고 빠르게 생성/수정/삭제하세요." />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <NoticePanel notice={notice} />
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadDocuments();
            }}
          />

          <div className="editor-workbench">
            <aside className="editor-sidebar panel-soft">
              <div className="editor-sidebar-header">
                <h3 className="section-title">분류 보기</h3>
              </div>

              <div className="editor-view-toggle" role="tablist" aria-label="에디터 사이드바 보기 전환">
                <button
                  aria-selected={sidebarMode === "DOCUMENT"}
                  className={`editor-view-toggle-button${sidebarMode === "DOCUMENT" ? " is-active" : ""}`}
                  onClick={() => {
                    setSidebarMode("DOCUMENT");
                    setOpenMenuDocId(null);
                  }}
                  role="tab"
                  type="button"
                >
                  문서로 분류
                </button>
                <button
                  aria-selected={sidebarMode === "NODE"}
                  className={`editor-view-toggle-button${sidebarMode === "NODE" ? " is-active" : ""}`}
                  onClick={() => {
                    setSidebarMode("NODE");
                    setOpenMenuDocId(null);
                  }}
                  role="tab"
                  type="button"
                >
                  노드로 분류
                </button>
              </div>

              <div className="editor-sidebar-header">
                <button
                  className="btn btn-secondary btn-small"
                  disabled={!state.workspaceId || creatingParentId === ROOT_CREATE_ID}
                  onClick={() => {
                    void createDocument(null);
                  }}
                  type="button"
                >
                  새 페이지
                </button>
                {sidebarMode === "NODE" ? (
                  <button
                    className="btn btn-ghost btn-small"
                    disabled={!state.workspaceId || loadingNodeTree}
                    onClick={() => {
                      void loadNodeTree();
                    }}
                    type="button"
                  >
                    {loadingNodeTree ? "갱신 중..." : "노드 갱신"}
                  </button>
                ) : null}
              </div>

              <label className="field-label" htmlFor="editor-tree-search">
                검색
              </label>
              <input
                className="field-input"
                id="editor-tree-search"
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder={sidebarMode === "NODE" ? "노드/문서 제목 검색" : "페이지 제목 검색"}
                value={searchQuery}
              />

              {loadingList ? <p className="muted">문서 목록을 불러오는 중입니다...</p> : null}
              {sidebarMode === "NODE" ? (
                <>
                  <ErrorPanel
                    error={nodeTreeError}
                    onRetry={() => {
                      void loadNodeTree();
                    }}
                  />
                  {loadingNodeTree ? <p className="muted">노드 분류 트리를 불러오는 중입니다...</p> : null}
                </>
              ) : null}
              {!loadingList && state.workspaceId && documents.length === 0 ? (
                <EmptyState title="문서가 없습니다" description="새 페이지 버튼으로 첫 문서를 만들어 보세요." />
              ) : null}
              {sidebarMode === "DOCUMENT" ? (
                <>
                  {!loadingList && state.workspaceId && documents.length > 0 && filteredTreeRoots.length === 0 ? (
                    <EmptyState title="검색 결과가 없습니다" description="검색어를 지우거나 다른 키워드를 입력해 보세요." />
                  ) : null}
                  {!loadingList && filteredTreeRoots.length > 0 ? <ul className="editor-tree-list">{renderTree(filteredTreeRoots, 0)}</ul> : null}
                </>
              ) : (
                <>
                  {!loadingNodeTree && state.workspaceId && nodeTrees.length === 0 ? (
                    <EmptyState title="노드가 없습니다" description="문서 분류가 끝난 뒤 노드로 전환해 보세요." />
                  ) : null}
                  {!loadingNodeTree && state.workspaceId && nodeTrees.length > 0 && filteredNodeTrees.length === 0 ? (
                    <EmptyState title="검색 결과가 없습니다" description="검색어를 지우거나 다른 키워드를 입력해 보세요." />
                  ) : null}
                  {!loadingNodeTree && filteredNodeTrees.length > 0 ? <ul className="editor-node-list">{renderNodeTree(filteredNodeTrees, 0)}</ul> : null}
                </>
              )}
            </aside>

            <div className="editor-main panel-soft">
              {!state.workspaceId ? null : (
                <>
                  <div className="editor-main-header">
                    <div>
                      <h3 className="section-title">{selectedDocument ? selectedDocument.title : "페이지를 선택하세요"}</h3>
                      <p className="section-subtitle">
                        {selectedParentTitle ?? "왼쪽 트리에서 페이지를 선택하거나 새로 만드세요."}
                      </p>
                    </div>
                    <div className="action-row">
                      <button
                        className="btn btn-secondary"
                        disabled={!selectedDocumentId || creatingParentId === selectedDocumentId}
                        onClick={() => {
                          if (selectedDocumentId) {
                            void createDocument(selectedDocumentId);
                          }
                        }}
                        type="button"
                      >
                        하위 페이지
                      </button>
                      <button
                        className="btn btn-primary"
                        disabled={!selectedDocumentId || saving || loadingDocument || deletingDocumentId === selectedDocumentId}
                        onClick={() => {
                          void saveSelectedDocument();
                        }}
                        type="button"
                      >
                        {saving ? "저장 중..." : "저장"}
                      </button>
                      <button
                        className="btn btn-ghost"
                        disabled={!selectedDocumentId || deletingDocumentId === selectedDocumentId}
                        onClick={() => {
                          if (selectedDocumentId) {
                            void deleteDocument(selectedDocumentId);
                          }
                        }}
                        type="button"
                      >
                        삭제
                      </button>
                    </div>
                  </div>

                  <div className="field-stack">
                    <label className="field-label" htmlFor="editor-title">
                      제목
                    </label>
                    <input
                      className="field-input"
                      disabled={!selectedDocumentId || loadingDocument || deletingDocumentId === selectedDocumentId}
                      id="editor-title"
                      onChange={(event) => setDraftTitle(event.target.value)}
                      placeholder="문서 제목"
                      value={draftTitle}
                    />

                    <label className="field-label" htmlFor="editor-body">
                      본문 (Markdown)
                    </label>
                    <textarea
                      className="field-textarea editor-page-textarea"
                      disabled={!selectedDocumentId || loadingDocument || deletingDocumentId === selectedDocumentId}
                      id="editor-body"
                      onChange={(event) => setDraftBody(event.target.value)}
                      placeholder="여기에 문서를 작성하세요. Cmd/Ctrl + S로 저장할 수 있습니다."
                      rows={18}
                      value={draftBody}
                    />

                    {selectedDocumentId ? (
                      <p className="muted">
                        {isDirty ? "저장되지 않은 변경사항이 있습니다." : "저장 완료 상태입니다."} 단축키: Cmd/Ctrl + S
                      </p>
                    ) : (
                      <EmptyState title="페이지를 선택하세요" description="왼쪽에서 페이지를 선택하면 즉시 편집할 수 있습니다." />
                    )}
                  </div>
                </>
              )}
            </div>
          </div>
        </section>
      </Layout>
    );
  }

  function WorkspaceDocumentEditorPage() {
    const params = useParams();
    const navigate = useNavigate();
    const workspaceId = params.workspaceId ?? state.workspaceId;
    const documentId = params.documentId ?? null;
    const [doc, setDoc] = useState<DocumentItem | null>(null);
    const [draftTitle, setDraftTitle] = useState("");
    const [draftBody, setDraftBody] = useState("");
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [creatingChild, setCreatingChild] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const [error, setError] = useState<UiError | null>(null);
    const [notice, setNotice] = useState<UiNotice | null>(null);

    const loadDocument = useCallback(async () => {
      if (!documentId || !state.workspaceId) {
        setDoc(null);
        setDraftTitle("");
        setDraftBody("");
        return;
      }
      setLoading(true);
      setError(null);
      try {
        const payload = await api.request<DocumentItem>(`/documents/${documentId}`, {}, true);
        setDoc(payload);
        setDraftTitle(payload.title);
        setDraftBody(payload.body_markdown ?? "");
      } catch (e) {
        setError(toUiError(e, "문서를 불러오지 못했습니다"));
      } finally {
        setLoading(false);
      }
    }, [api, documentId, state.workspaceId]);

    useEffect(() => {
      void loadDocument();
    }, [loadDocument]);

    const isDirty = useMemo(() => {
      if (!doc) {
        return false;
      }
      return draftTitle.trim() !== doc.title.trim() || draftBody !== (doc.body_markdown ?? "");
    }, [doc, draftBody, draftTitle]);

    const saveDocument = useCallback(async () => {
      if (!documentId || !doc || !state.workspaceId || typeof doc.version !== "number") {
        return;
      }
      setSaving(true);
      setError(null);
      setNotice(null);
      try {
        await api.request<void>(
          `/documents/${documentId}`,
          {
            method: "PATCH",
            body: JSON.stringify({
              version: doc.version,
              title: draftTitle.trim() || "제목 없음",
              body_markdown: draftBody
            })
          },
          true
        );
        await loadDocument();
        setNotice({ tone: "success", message: "문서를 저장했습니다." });
      } catch (e) {
        setError(toUiError(e, "문서 저장에 실패했습니다"));
      } finally {
        setSaving(false);
      }
    }, [api, doc, documentId, draftBody, draftTitle, loadDocument, state.workspaceId]);

    const createChildPage = useCallback(async () => {
      if (!state.workspaceId || !documentId || !workspaceId) {
        return;
      }
      setCreatingChild(true);
      setError(null);
      setNotice(null);
      try {
        const created = await api.request<{ id: string }>(
          "/documents",
          {
            method: "POST",
            body: JSON.stringify({
              title: `${(draftTitle.trim() || doc?.title || "새 페이지")} 하위 페이지`,
              body_markdown: "",
              source_type: "EDITOR",
              parent_document_id: documentId
            })
          },
          true
        );
        navigate(workspaceDocumentPath(workspaceId, created.id));
      } catch (e) {
        setError(toUiError(e, "하위 페이지 생성에 실패했습니다"));
      } finally {
        setCreatingChild(false);
      }
    }, [api, doc?.title, documentId, draftTitle, navigate, state.workspaceId, workspaceId]);

    const deleteDocument = useCallback(async () => {
      if (!documentId || !state.workspaceId || !workspaceId) {
        return;
      }
      if (!window.confirm("현재 페이지를 삭제하시겠습니까?")) {
        return;
      }
      setDeleting(true);
      setError(null);
      setNotice(null);
      try {
        await api.request<void>(
          `/documents/${documentId}`,
          {
            method: "DELETE"
          },
          true
        );
        setNotice({ tone: "success", message: "페이지를 삭제했습니다." });
        navigate(workspaceRootPath(workspaceId));
      } catch (e) {
        setError(toUiError(e, "페이지 삭제에 실패했습니다"));
      } finally {
        setDeleting(false);
      }
    }, [api, documentId, navigate, state.workspaceId, workspaceId]);

    useEffect(() => {
      const onSaveShortcut = (event: KeyboardEvent) => {
        if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "s") {
          return;
        }
        if (!documentId || saving) {
          return;
        }
        event.preventDefault();
        void saveDocument();
      };
      window.addEventListener("keydown", onSaveShortcut);
      return () => {
        window.removeEventListener("keydown", onSaveShortcut);
      };
    }, [documentId, saveDocument, saving]);

    return (
      <Layout api={api}>
        <section className="panel">
          <div className="page-breadcrumb">
            <span>{state.workspaceName ?? "Workspace"}</span>
            <span>/</span>
            <span>Page</span>
            <span>/</span>
            <strong>{doc?.title ?? "문서"}</strong>
          </div>
          <PageHeader
            title={doc?.title ?? "문서 페이지"}
            subtitle="페이지를 바로 편집하고 저장 상태를 확인하세요."
            action={
              <div className="action-row">
                <button className="btn btn-secondary" disabled={!documentId || creatingChild || deleting} onClick={() => void createChildPage()} type="button">
                  {creatingChild ? "생성 중..." : "하위 페이지"}
                </button>
                <button className="btn btn-primary" disabled={!documentId || saving || deleting} onClick={() => void saveDocument()} type="button">
                  {saving ? "저장 중..." : "저장"}
                </button>
                <button className="btn btn-ghost" disabled={!documentId || deleting} onClick={() => void deleteDocument()} type="button">
                  {deleting ? "삭제 중..." : "삭제"}
                </button>
              </div>
            }
          />
          <NoticePanel notice={notice} />
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadDocument();
            }}
          />

          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}

          {loading ? <p className="muted">문서를 불러오는 중입니다...</p> : null}

          {doc ? (
            <div className="editor-doc-layout">
              <div className="editor-doc-main">
                <div className="field-stack">
                  <label className="field-label" htmlFor="workspace-doc-title">
                    제목
                  </label>
                  <input
                    className="field-input"
                    id="workspace-doc-title"
                    onChange={(event) => setDraftTitle(event.target.value)}
                    placeholder="문서 제목"
                    value={draftTitle}
                  />

                  <label className="field-label" htmlFor="workspace-doc-body">
                    본문 (Markdown)
                  </label>
                  <textarea
                    className="field-textarea editor-page-textarea"
                    id="workspace-doc-body"
                    onChange={(event) => setDraftBody(event.target.value)}
                    placeholder="여기에 내용을 입력하세요. Cmd/Ctrl + S로 저장할 수 있습니다."
                    rows={22}
                    value={draftBody}
                  />
                </div>
                <p className="muted">{isDirty ? "저장되지 않은 변경사항이 있습니다." : "모든 변경사항이 저장되었습니다."}</p>
              </div>

              <aside className="editor-doc-side panel-soft">
                <h3 className="section-title">문서 컨텍스트</h3>
                <p className="section-subtitle">트리 위치/추천 스냅샷과 파이프라인 상세를 바로 확인할 수 있습니다.</p>
                <div className="pipeline-grid">
                  <StatusChip label="수집" value={doc.pipeline_status.ingest} />
                  <StatusChip label="임베딩" value={doc.pipeline_status.embed} />
                  <StatusChip label="인덱스" value={doc.pipeline_status.index} />
                  <StatusChip label="트리" value={doc.pipeline_status.tree} />
                </div>
                {workspaceId ? (
                  <div className="field-stack">
                    <Link className="btn btn-secondary" to={workspaceViewPath(workspaceId, "tree")}>
                      트리에서 위치/이동 보기
                    </Link>
                    <Link className="btn btn-ghost" to={workspaceDocumentDetailPath(workspaceId, doc.id)}>
                      파이프라인 상세
                    </Link>
                  </div>
                ) : null}
              </aside>
            </div>
          ) : (
            <EmptyState title="문서를 찾을 수 없습니다" description="좌측 Pages에서 문서를 선택해 주세요." />
          )}
        </section>
      </Layout>
    );
  }

  function DocumentPage() {
    const params = useParams();
    const [doc, setDoc] = useState<DocumentItem | null>(null);
    const [uploadFile, setUploadFile] = useState<File | null>(null);
    const [explain, setExplain] = useState<ExplainResponse | null>(null);
    const [explainError, setExplainError] = useState<UiError | null>(null);
    const [explainNotice, setExplainNotice] = useState<UiNotice | null>(null);
    const [explainLoading, setExplainLoading] = useState(false);
    const [acceptingExplain, setAcceptingExplain] = useState(false);
    const [explainDrawerOpen, setExplainDrawerOpen] = useState(false);
    const [uploadProgress, setUploadProgress] = useState(0);
    const [uploading, setUploading] = useState(false);
    const [documentError, setDocumentError] = useState<UiError | null>(null);
    const [uploadError, setUploadError] = useState<UiError | null>(null);
    const [pipelineRetryError, setPipelineRetryError] = useState<UiError | null>(null);
    const [pipelineRetryNotice, setPipelineRetryNotice] = useState<UiNotice | null>(null);
    const [pipelineRetrying, setPipelineRetrying] = useState(false);
    const failedPipelineStage = useMemo(() => (doc ? getFailedPipelineStage(doc) : null), [doc]);

    useEffect(() => {
      setPipelineRetryError(null);
      setPipelineRetryNotice(null);
    }, [params.documentId]);

    const loadDocument = useCallback(async () => {
      if (!params.documentId || !state.workspaceId) {
        return;
      }
      try {
        const payload = await api.request<DocumentItem>(`/documents/${params.documentId}`, {}, true);
        setDoc(payload);
        setDocumentError(null);
      } catch (e) {
        setDocumentError(toUiError(e, "문서를 불러오지 못했습니다"));
      }
    }, [api, params.documentId, state.workspaceId]);

    const uploadSelectedFile = useCallback(async () => {
      if (!uploadFile || !params.documentId || !state.workspaceId) {
        return;
      }

      setUploadError(null);
      setUploadProgress(0);
      setUploading(true);

      try {
        const presign = await api.request<{ attachment_id: string; upload_url: string }>(
          "/attachments/presign",
          {
            method: "POST",
            body: JSON.stringify({
              document_id: params.documentId,
              filename: uploadFile.name,
              content_type: uploadFile.type || "application/octet-stream",
              size: uploadFile.size
            })
          },
          true
        );

        const uploadOrigin = (() => {
          try {
            return new URL(presign.upload_url).origin;
          } catch {
            return "스토리지 서버";
          }
        })();

        await new Promise<void>((resolve, reject) => {
          const xhr = new XMLHttpRequest();
          xhr.open("PUT", presign.upload_url);
          xhr.setRequestHeader("Content-Type", uploadFile.type || "application/octet-stream");
          xhr.upload.onprogress = (event) => {
            if (!event.lengthComputable) {
              return;
            }
            const percent = Math.round((event.loaded / event.total) * 100);
            setUploadProgress(percent);
          };
          xhr.onerror = () =>
            reject(new Error(`스토리지 업로드 연결에 실패했습니다 (${uploadOrigin}). S3_ENDPOINT 또는 CORS 설정을 확인하세요.`));
          xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
              setUploadProgress(100);
              resolve();
            } else {
              reject(new Error(`스토리지 업로드 응답 오류(${xhr.status})가 발생했습니다. 엔드포인트(${uploadOrigin})를 확인하세요.`));
            }
          };
          xhr.send(uploadFile);
        });

        await api.request(
          "/attachments/complete",
          {
            method: "POST",
            body: JSON.stringify({ attachment_id: presign.attachment_id })
          },
          true
        );

        await loadDocument();
      } catch (e) {
        setUploadError(toUiError(e, "업로드에 실패했습니다"));
      } finally {
        setUploading(false);
      }
    }, [api, loadDocument, params.documentId, state.workspaceId, uploadFile]);

    useEffect(() => {
      void loadDocument();
    }, [loadDocument]);

    const loadExplain = useCallback(async () => {
      if (!params.documentId) {
        return;
      }

      setExplainLoading(true);
      setExplainError(null);
      try {
        const payload = await api.request<ExplainResponse>(`/documents/${params.documentId}/explain`, {}, true);
        setExplain(payload);
        setExplainDrawerOpen(true);
        setExplainNotice(null);
      } catch (e) {
        setExplainError(toUiError(e, "설명 정보를 불러오지 못했습니다"));
      } finally {
        setExplainLoading(false);
      }
    }, [api, params.documentId]);

    const acceptExplain = useCallback(async () => {
      if (!params.documentId) {
        return;
      }
      setAcceptingExplain(true);
      setExplainError(null);
      try {
        await api.request<void>(
          `/documents/${params.documentId}/explain/accept`,
          {
            method: "POST"
          },
          true
        );
        setExplainNotice({ tone: "success", message: "현재 자동 배치를 수용했습니다." });
      } catch (e) {
        setExplainError(toUiError(e, "설명 수용 처리에 실패했습니다"));
      } finally {
        setAcceptingExplain(false);
      }
    }, [api, params.documentId]);

    const retryFailedPipelineStage = useCallback(async () => {
      if (!params.documentId || !failedPipelineStage) {
        return;
      }
      setPipelineRetrying(true);
      setPipelineRetryError(null);
      try {
        await api.request<void>(
          `/documents/${params.documentId}/pipeline/retry`,
          {
            method: "POST",
            body: JSON.stringify({ stage: failedPipelineStage })
          },
          true
        );
        setPipelineRetryNotice({
          tone: "info",
          message: `${PIPELINE_STAGE_LABEL[failedPipelineStage]} 단계 재실행을 요청했습니다.`
        });
        await loadDocument();
      } catch (e) {
        setPipelineRetryError(toUiError(e, "실패 단계 재실행에 실패했습니다"));
      } finally {
        setPipelineRetrying(false);
      }
    }, [api, failedPipelineStage, loadDocument, params.documentId]);

    useEffect(() => {
      if (!doc) {
        return;
      }
      const isProcessing =
        doc.status === "PROCESSING" ||
        [doc.pipeline_status.ingest, doc.pipeline_status.embed, doc.pipeline_status.index, doc.pipeline_status.tree].some(
          (v) => v === "RUNNING" || v === "PENDING"
        );
      if (!isProcessing) {
        return;
      }

      const timer = setInterval(() => {
        void loadDocument();
      }, 2000);
      return () => clearInterval(timer);
    }, [doc, loadDocument]);

    const explainNeighbors = explain?.rationale?.evidence?.neighbors ?? [];
    const explainReasonCodes = explain?.rationale?.evidence?.reason_codes ?? [];
    const scorePercent = (value?: number | null): string => {
      if (typeof value !== "number" || Number.isNaN(value)) {
        return "0%";
      }
      const clamped = Math.max(0, Math.min(1, value));
      return `${Math.round(clamped * 100)}%`;
    };

    return (
      <Layout api={api}>
        <section className="panel">
          <PageHeader
            title={doc?.title ?? "문서 상세"}
            subtitle="처리 단계, 첨부파일, 배치 근거를 확인하세요."
          />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <ErrorPanel
            error={documentError}
            onRetry={() => {
              void loadDocument();
            }}
          />

          {doc ? (
            <div className="document-meta-grid">
              <div className="meta-block">
                <span className="meta-label">문서 식별자</span>
                <code>{doc.id}</code>
              </div>
              <div className="meta-block">
                <span className="meta-label">상태</span>
                <StatusChip label="문서" value={doc.status} />
              </div>
              <div className="meta-block meta-block-wide">
                <span className="meta-label">파이프라인</span>
                <div className="pipeline-grid">
                  <StatusChip label="수집" value={doc.pipeline_status.ingest} />
                  <StatusChip label="임베딩" value={doc.pipeline_status.embed} />
                  <StatusChip label="인덱스" value={doc.pipeline_status.index} />
                  <StatusChip label="트리" value={doc.pipeline_status.tree} />
                </div>
              </div>
              {doc.pipeline_status.failure_reason ? (
                <div className="meta-block meta-block-wide meta-warning">
                  <span className="meta-label">실패 사유</span>
                  <span>{doc.pipeline_status.failure_reason}</span>
                </div>
              ) : null}
              {failedPipelineStage ? (
                <div className="meta-block meta-block-wide">
                  <span className="meta-label">실패 단계 재실행</span>
                  <p className="muted">현재 실패 단계는 {PIPELINE_STAGE_LABEL[failedPipelineStage]}입니다.</p>
                  <div className="action-row">
                    <button
                      className="btn btn-secondary"
                      disabled={pipelineRetrying}
                      onClick={() => {
                        void retryFailedPipelineStage();
                      }}
                      type="button"
                    >
                      {pipelineRetrying ? "재실행 요청 중..." : `${PIPELINE_STAGE_LABEL[failedPipelineStage]} 재실행`}
                    </button>
                  </div>
                  <NoticePanel notice={pipelineRetryNotice} />
                  <ErrorPanel
                    error={pipelineRetryError}
                    onRetry={() => {
                      void retryFailedPipelineStage();
                    }}
                  />
                </div>
              ) : null}
              <div className="meta-block meta-block-wide">
                <span className="meta-label">첨부파일 ({doc.attachments.length})</span>
                {doc.attachments.length === 0 ? (
                  <span className="muted">첨부파일이 없습니다.</span>
                ) : (
                  <ul className="simple-list">
                    {doc.attachments.map((attachment) => (
                      <li key={attachment.id}>
                        <code>{attachment.id}</code> ({attachment.content_type}, {attachment.size} 바이트)
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          ) : (
            <p className="muted">문서를 불러오는 중입니다...</p>
          )}
        </section>

        <section className="panel">
          <h2 className="section-title">첨부파일 업로드</h2>
          <p className="section-subtitle">파일을 끌어 놓거나 선택해 문서에 첨부하세요.</p>

          <div
            className="dropzone"
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => {
              event.preventDefault();
              const dropped = event.dataTransfer.files?.[0] ?? null;
              setUploadFile(dropped);
            }}
          >
            여기에 파일을 놓으세요
          </div>

          <input className="field-input" onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)} type="file" />
          <p className="muted">선택 파일: {uploadFile ? `${uploadFile.name} (${uploadFile.size} 바이트)` : "없음"}</p>

          <div className="progress-row">
            <div className="progress-track">
              <div className="progress-fill" style={{ width: `${uploadProgress}%` }} />
            </div>
            <span className="progress-value">{uploadProgress}%</span>
          </div>

          <ErrorPanel
            error={uploadError}
            onRetry={() => {
              void uploadSelectedFile();
            }}
          />

          <div className="action-row">
            <button
              className="btn btn-primary"
              disabled={!uploadFile || !params.documentId || uploading || !state.workspaceId}
              onClick={() => void uploadSelectedFile()}
              type="button"
            >
              {uploading ? "업로드 중..." : "업로드"}
            </button>
            {uploadError ? (
              <button className="btn btn-secondary" onClick={() => void uploadSelectedFile()} type="button">
                업로드 재시도
              </button>
            ) : null}
          </div>
        </section>

        <section className="panel">
          <div className="section-header-inline">
            <div>
              <h2 className="section-title">배치 설명</h2>
              <p className="section-subtitle">이웃 근거와 사유 코드를 확인하고 바로 수용하거나 이동할 수 있습니다.</p>
            </div>
            <button
              className="btn btn-secondary"
              disabled={explainLoading}
              onClick={() => {
                if (explain) {
                  setExplainDrawerOpen(true);
                  return;
                }
                void loadExplain();
              }}
              type="button"
            >
              {explainLoading ? "불러오는 중..." : explain ? "왜 여기?" : "설명 불러오기"}
            </button>
          </div>
          <p className="muted explain-summary">
            최소 근거(이웃 2~3개, 채널 점수, 사유 코드)만 보여주며 본문 원문은 표시하지 않습니다.
          </p>
          <NoticePanel notice={explainNotice} />
          <ErrorPanel
            error={explainError}
            onRetry={() => {
              void loadExplain();
            }}
          />

          {explainDrawerOpen ? (
            <div
              className="explain-drawer-backdrop"
              onClick={() => setExplainDrawerOpen(false)}
              role="presentation"
            >
              <aside
                className="explain-drawer"
                onClick={(event) => event.stopPropagation()}
                role="dialog"
                aria-modal="true"
                aria-label="배치 설명 상세"
              >
                <div className="explain-drawer-header">
                  <h3>왜 여기에 배치됐나요?</h3>
                  <div className="action-row">
                    <button
                      className="btn btn-ghost btn-small"
                      disabled={explainLoading}
                      onClick={() => void loadExplain()}
                      type="button"
                    >
                      {explainLoading ? "새로고침 중..." : "새로고침"}
                    </button>
                    <button
                      className="btn btn-ghost btn-small"
                      onClick={() => setExplainDrawerOpen(false)}
                      type="button"
                    >
                      닫기
                    </button>
                  </div>
                </div>

                {explainLoading ? (
                  <p className="muted">설명 데이터를 불러오는 중입니다...</p>
                ) : explain ? (
                  <div className="explain-drawer-body">
                    <div className="explain-block">
                      <h4>요약 문장</h4>
                      <p className="muted">{explain.rationale?.llm_sentence ?? "요약 문장이 없습니다."}</p>
                    </div>

                    <div className="explain-block">
                      <h4>사유 코드</h4>
                      {explainReasonCodes.length ? (
                        <div className="explain-chip-row">
                          {explainReasonCodes.map((code) => (
                            <span className="explain-chip" key={code}>
                              {code}
                            </span>
                          ))}
                        </div>
                      ) : (
                        <p className="muted">사유 코드가 없습니다.</p>
                      )}
                    </div>

                    <div className="explain-block">
                      <h4>근거 이웃</h4>
                      {explainNeighbors.length ? (
                        <ul className="neighbor-evidence-list">
                          {explainNeighbors.map((neighbor) => {
                            const scoreRows = [
                              { label: "semantic", value: neighbor.channel_scores?.semantic },
                              { label: "lexical", value: neighbor.channel_scores?.lexical },
                              { label: "final", value: neighbor.channel_scores?.final }
                            ];
                            return (
                              <li className="neighbor-evidence-item" key={neighbor.document_id}>
                                <div className="neighbor-evidence-head">
                                  <Link
                                    className="doc-link"
                                    to={
                                      state.workspaceId
                                        ? workspaceDocumentDetailPath(state.workspaceId, neighbor.document_id)
                                        : `/documents/${neighbor.document_id}`
                                    }
                                  >
                                    {neighbor.title || neighbor.document_id}
                                  </Link>
                                  <span className="score-pill">{neighbor.edge_decision?.reason_code ?? "UNKNOWN"}</span>
                                </div>
                                <div className="evidence-score-grid">
                                  {scoreRows.map((row) => (
                                    <div className="evidence-score-row" key={`${neighbor.document_id}-${row.label}`}>
                                      <span>{row.label}</span>
                                      <div className="evidence-score-track">
                                        <div className="evidence-score-fill" style={{ width: scorePercent(row.value) }} />
                                      </div>
                                      <span className="evidence-score-value">
                                        {typeof row.value === "number" ? row.value.toFixed(2) : "-"}
                                      </span>
                                    </div>
                                  ))}
                                </div>
                              </li>
                            );
                          })}
                        </ul>
                      ) : (
                        <p className="muted">근거 이웃이 없습니다.</p>
                      )}
                    </div>

                    <div className="action-row explain-drawer-actions">
                      <button
                        className="btn btn-primary"
                        disabled={acceptingExplain}
                        onClick={() => void acceptExplain()}
                        type="button"
                      >
                        {acceptingExplain ? "수용 처리 중..." : "수용"}
                      </button>
                      <Link
                        className="btn btn-secondary"
                        onClick={() => setExplainDrawerOpen(false)}
                        to={state.workspaceId ? workspaceViewPath(state.workspaceId, "tree") : "/tree"}
                      >
                        다른 폴더로 이동
                      </Link>
                    </div>
                  </div>
                ) : (
                  <p className="muted">설명 데이터가 아직 없습니다.</p>
                )}
              </aside>
            </div>
          ) : null}
        </section>
      </Layout>
    );
  }

  function SearchPage() {
    const [q, setQ] = useState("");
    const [results, setResults] = useState<SearchResponse["items"]>([]);
    const [error, setError] = useState<UiError | null>(null);

    const executeSearch = useCallback(async () => {
      if (!q.trim() || !state.workspaceId) {
        return;
      }
      setError(null);
      try {
        const response = await api.request<SearchResponse>(`/search?q=${encodeURIComponent(q)}`, {}, true);
        setResults(response.items);
      } catch (e) {
        setError(toUiError(e, "검색에 실패했습니다"));
      }
    }, [api, q, state.workspaceId]);

    return (
      <Layout api={api}>
        <section className="panel">
          <PageHeader title="검색" subtitle="워크스페이스 범위 검색 인덱스에서 문서를 찾습니다." />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <ErrorPanel
            error={error}
            onRetry={() => {
              void executeSearch();
            }}
          />

          <form
            className="search-row"
            onSubmit={(event) => {
              event.preventDefault();
              void executeSearch();
            }}
          >
            <input className="field-input" onChange={(e) => setQ(e.target.value)} placeholder="문서 검색어 입력" value={q} />
            <button className="btn btn-primary" disabled={!state.workspaceId || !q.trim()} type="submit">
              검색
            </button>
          </form>

          {state.workspaceId && results.length === 0 && q.trim() ? (
            <EmptyState title="검색 결과가 없습니다" description="검색어를 넓히거나 인덱싱 완료 여부를 확인하세요." />
          ) : null}

          <ul className="search-results">
            {results.map((item) => (
              <li className="search-result-item" key={item.document_id}>
                <Link
                  className="doc-link"
                  to={state.workspaceId ? workspaceDocumentDetailPath(state.workspaceId, item.document_id) : `/documents/${item.document_id}`}
                >
                  {item.title}
                </Link>
                <span className="score-pill">점수 {item.score.toFixed(2)}</span>
              </li>
            ))}
          </ul>
        </section>
      </Layout>
    );
  }

  function QuestionsPage() {
    const [questions, setQuestions] = useState<QuestionItem[]>([]);
    const [openCount, setOpenCount] = useState(0);
    const [loading, setLoading] = useState(false);
    const [answeringQuestionId, setAnsweringQuestionId] = useState<string | null>(null);
    const [error, setError] = useState<UiError | null>(null);
    const [notice, setNotice] = useState<UiNotice | null>(null);

    const loadQuestions = useCallback(async () => {
      if (!state.workspaceId) {
        setQuestions([]);
        setOpenCount(0);
        return;
      }
      setLoading(true);
      setError(null);
      try {
        const response = await api.request<QuestionListResponse>("/questions?status=OPEN&limit=20", {}, true);
        setQuestions(response.items);
        setOpenCount(response.open_count);
      } catch (e) {
        setError(toUiError(e, "질문 목록을 불러오지 못했습니다"));
      } finally {
        setLoading(false);
      }
    }, [api, state.workspaceId]);

    useEffect(() => {
      void loadQuestions();
    }, [loadQuestions, state.workspaceId]);

    const answerQuestion = useCallback(
      async (questionId: string, answer: string) => {
        const previous = questions;
        setAnsweringQuestionId(questionId);
        setError(null);
        setQuestions((prev) => prev.filter((item) => item.id !== questionId));
        setOpenCount((prev) => Math.max(0, prev - 1));
        try {
          await api.request(
            `/questions/${questionId}/answer`,
            {
              method: "POST",
              body: JSON.stringify({ answer })
            },
            true
          );
          setNotice({ tone: "success", message: "질문 답변이 반영되었습니다." });
        } catch (e) {
          setQuestions(previous);
          setOpenCount(previous.length);
          setError(toUiError(e, "질문 답변 처리에 실패했습니다"));
        } finally {
          setAnsweringQuestionId(null);
        }
      },
      [api, questions]
    );

    return (
      <Layout api={api}>
        <section className="panel">
          <PageHeader
            title="질문 인박스"
            subtitle="낮은 확신 배치를 빠르게 해소하기 위한 2지선다 질문입니다."
            action={
              <button className="btn btn-secondary" disabled={!state.workspaceId || loading} onClick={() => void loadQuestions()} type="button">
                {loading ? "새로고침 중..." : "새로고침"}
              </button>
            }
          />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <NoticePanel notice={notice} />
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadQuestions();
            }}
          />

          {state.workspaceId ? <p className="muted">열린 질문 {openCount}건</p> : null}

          {state.workspaceId && questions.length === 0 ? (
            <EmptyState title="열린 질문이 없습니다" description="현재 답변이 필요한 질문이 없습니다." />
          ) : null}

          <div className="question-grid">
            {questions.map((question) => {
              const payload = question.payload as Record<string, unknown>;
              const disabled = answeringQuestionId === question.id;
              if (question.question_type === "DOC_CLUSTER_CHOICE") {
                const optionA = (payload.option_a as Record<string, unknown> | undefined) ?? {};
                const optionB = (payload.option_b as Record<string, unknown> | undefined) ?? {};
                return (
                  <article className="panel panel-soft question-card" key={question.id}>
                    <h3 className="section-title">문서 폴더 선택</h3>
                    <p className="section-subtitle">{(payload.document_title as string | undefined) ?? question.document_id}</p>
                    <p className="muted">
                      영향도 {Math.round(Math.max(0, Math.min(1, question.impact_score)) * 100)}%
                    </p>
                    <div className="action-row">
                      <button
                        className="btn btn-primary"
                        disabled={disabled}
                        onClick={() => void answerQuestion(question.id, "A")}
                        type="button"
                      >
                        A: {(optionA.label as string | undefined) ?? "후보 A"}
                      </button>
                      <button
                        className="btn btn-secondary"
                        disabled={disabled}
                        onClick={() => void answerQuestion(question.id, "B")}
                        type="button"
                      >
                        B: {(optionB.label as string | undefined) ?? "후보 B"}
                      </button>
                    </div>
                  </article>
                );
              }

              const leftTitle = (payload.doc_a_title as string | undefined) ?? (payload.doc_a_id as string | undefined) ?? question.document_id;
              const rightTitle = (payload.doc_b_title as string | undefined) ?? (payload.doc_b_id as string | undefined) ?? "-";
              return (
                <article className="panel panel-soft question-card" key={question.id}>
                  <h3 className="section-title">문서 관계 확인</h3>
                  <p className="section-subtitle">
                    {leftTitle} vs {rightTitle}
                  </p>
                  <p className="muted">
                    영향도 {Math.round(Math.max(0, Math.min(1, question.impact_score)) * 100)}%
                  </p>
                  <div className="action-row">
                    <button
                      className="btn btn-primary"
                      disabled={disabled}
                      onClick={() => void answerQuestion(question.id, "SAME")}
                      type="button"
                    >
                      같은 그룹
                    </button>
                    <button
                      className="btn btn-secondary"
                      disabled={disabled}
                      onClick={() => void answerQuestion(question.id, "DIFF")}
                      type="button"
                    >
                      다른 그룹
                    </button>
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      </Layout>
    );
  }

  function TrashPage() {
    const [documents, setDocuments] = useState<DocumentItem[]>([]);
    const [loading, setLoading] = useState(false);
    const [restoringDocumentId, setRestoringDocumentId] = useState<string | null>(null);
    const [error, setError] = useState<UiError | null>(null);
    const [notice, setNotice] = useState<UiNotice | null>(null);

    const loadTrashDocuments = useCallback(async () => {
      if (!state.workspaceId) {
        setDocuments([]);
        return;
      }
      setLoading(true);
      setError(null);
      try {
        const response = await api.request<DocumentListResponse>("/documents/trash?page=0&size=200", {}, true);
        setDocuments(response.items);
      } catch (e) {
        setError(toUiError(e, "휴지통 목록을 불러오지 못했습니다"));
      } finally {
        setLoading(false);
      }
    }, [api, state.workspaceId]);

    useEffect(() => {
      void loadTrashDocuments();
    }, [loadTrashDocuments, state.workspaceId]);

    const restoreDocument = useCallback(
      async (documentId: string) => {
        if (!state.workspaceId || restoringDocumentId) {
          return;
        }
        setRestoringDocumentId(documentId);
        setError(null);
        setNotice(null);
        try {
          await api.request<void>(
            `/documents/${documentId}/restore`,
            {
              method: "POST"
            },
            true
          );
          setNotice({ tone: "success", message: "문서를 복원했습니다." });
          await loadTrashDocuments();
        } catch (e) {
          setError(toUiError(e, "문서 복원에 실패했습니다"));
        } finally {
          setRestoringDocumentId(null);
        }
      },
      [api, loadTrashDocuments, restoringDocumentId, state.workspaceId]
    );

    return (
      <Layout api={api}>
        <section className="panel">
          <PageHeader
            title="휴지통"
            subtitle="삭제된 문서를 확인하고 복원하세요."
            action={
              <button className="btn btn-secondary" disabled={!state.workspaceId || loading} onClick={() => void loadTrashDocuments()} type="button">
                {loading ? "새로고침 중..." : "새로고침"}
              </button>
            }
          />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <NoticePanel notice={notice} />
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadTrashDocuments();
            }}
          />

          {state.workspaceId && documents.length === 0 ? (
            <EmptyState title="휴지통이 비어 있습니다" description="삭제된 문서가 없습니다." />
          ) : null}

          <ul className="documents-list-view">
            {documents.map((document) => (
              <li className="documents-list-row" key={document.id}>
                <div className="documents-list-main">
                  <strong>{document.title}</strong>
                  <p className="muted">삭제 시각 {new Date(document.updated_at).toLocaleString("ko-KR")}</p>
                </div>
                <div className="action-row">
                  <button
                    className="btn btn-primary btn-small"
                    disabled={restoringDocumentId === document.id}
                    onClick={() => {
                      void restoreDocument(document.id);
                    }}
                    type="button"
                  >
                    {restoringDocumentId === document.id ? "복원 중..." : "복원"}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      </Layout>
    );
  }

  function TreePage() {
    const [tree, setTree] = useState<TreeActiveResponse | null>(null);
    const [selectedView, setSelectedView] = useState<TreeView>("topic");
    const [selectedNode, setSelectedNode] = useState<string | null>(null);
    const [docIdForMove, setDocIdForMove] = useState("");
    const [renameNodeId, setRenameNodeId] = useState("");
    const [newLabel, setNewLabel] = useState("");
    const [draggingDocId, setDraggingDocId] = useState<string | null>(null);
    const [dragSourceNodeId, setDragSourceNodeId] = useState<string | null>(null);
    const [treeError, setTreeError] = useState<UiError | null>(null);
    const [treeNotice, setTreeNotice] = useState<UiNotice | null>(null);
    const [isRebuilding, setIsRebuilding] = useState(false);
    const [includeDescendants, setIncludeDescendants] = useState(false);
    const isTopicView = selectedView === "topic";

    useEffect(() => {
      setSelectedView(loadTreeViewPreference(state.workspaceId));
      setSelectedNode(null);
      setIncludeDescendants(false);
    }, [state.workspaceId]);

    useEffect(() => {
      setIncludeDescendants(false);
    }, [selectedNode, selectedView]);

    const refreshTree = useCallback(async () => {
      if (!state.workspaceId) {
        setTree(null);
        setSelectedNode(null);
        setTreeNotice(null);
        return;
      }

      try {
        const params = new URLSearchParams({ view: selectedView });
        const payload = await api.request<TreeActiveResponse>(`/trees?${params.toString()}`, {}, true);
        setTree(payload);
        setSelectedNode((prev) => {
          if (!prev) {
            return TREE_INBOX_NODE_ID;
          }
          if (prev === TREE_INBOX_NODE_ID || prev === TREE_TEMPLATES_NODE_ID) {
            return prev;
          }
          if (payload.nodes.some((node) => node.id === prev)) {
            return prev;
          }
          return TREE_INBOX_NODE_ID;
        });
        setTreeError(null);
      } catch (e) {
        setTreeNotice(null);
        setTreeError(toUiError(e, "트리 로드에 실패했습니다"));
      }
    }, [api, selectedView, state.workspaceId]);

    const moveDocument = useCallback(
      async (documentId: string, fromNodeId: string | null, toNodeId: string, source: "DRAG" | "MANUAL" | "QUICK_CONFIRM" = "MANUAL") => {
        if (!isTopicView) {
          setTreeError({ message: "이동/피드백은 Topic 뷰에서만 지원됩니다.", status: null });
          return;
        }
        if (!tree) {
          return;
        }

        const snapshot = tree;
        setTree(moveDocumentInTree(tree, documentId, fromNodeId, toNodeId));

        try {
          await api.request(
            "/feedback/move",
            {
              method: "POST",
              body: JSON.stringify({
                document_id: documentId,
                from_node_id: fromNodeId,
                to_node_id: toNodeId,
                source
              })
            },
            true
          );
          await refreshTree();
        } catch (e) {
          setTree(snapshot);
          setTreeError(toUiError(e, "이동에 실패했습니다"));
        }
      },
      [api, isTopicView, refreshTree, tree]
    );

    useEffect(() => {
      setTreeNotice(null);
      void refreshTree();
    }, [refreshTree, state.workspaceId, selectedView]);

    const allDocuments = useMemo<TreeDocumentView[]>(() => {
      if (!tree) {
        return [];
      }
      return tree.nodes.flatMap((node) => {
        const summaries: TreeNodeDocumentSummary[] =
          node.document_summaries && node.document_summaries.length > 0
            ? node.document_summaries
            : node.documents.map((documentId) => ({ id: documentId, title: documentId }));
        return summaries.map((summary) => toTreeDocumentView(node, summary));
      });
    }, [tree]);

    const templateDocuments = useMemo(
      () => allDocuments.filter((document) => (document.quarantine_reason ?? "").toUpperCase() === "TEMPLATE"),
      [allDocuments]
    );

    const inboxDocuments = useMemo(
      () =>
        allDocuments.filter((document) => {
          const reason = (document.quarantine_reason ?? "").toUpperCase();
          if (reason === "TEMPLATE") {
            return false;
          }
          return Boolean(reason) || isUnsortedNodeLabel(document.node_label);
        }),
      [allDocuments]
    );

    const descendantNodeIdsByNode = useMemo<Map<string, Set<string>>>(() => {
      if (!tree) {
        return new Map();
      }
      const childrenByParent = new Map<string, string[]>();
      tree.nodes.forEach((node) => {
        if (!node.parent_id) {
          return;
        }
        const children = childrenByParent.get(node.parent_id) ?? [];
        children.push(node.id);
        childrenByParent.set(node.parent_id, children);
      });

      const cache = new Map<string, Set<string>>();
      const collectDescendants = (nodeId: string): Set<string> => {
        const cached = cache.get(nodeId);
        if (cached) {
          return cached;
        }
        const ids = new Set<string>([nodeId]);
        (childrenByParent.get(nodeId) ?? []).forEach((childId) => {
          collectDescendants(childId).forEach((id) => ids.add(id));
        });
        cache.set(nodeId, ids);
        return ids;
      };

      tree.nodes.forEach((node) => {
        collectDescendants(node.id);
      });
      return cache;
    }, [tree]);

    const selected = tree?.nodes.find((n) => n.id === selectedNode) ?? null;
    const selectedDirectDocuments = useMemo<TreeDocumentView[]>(() => {
      if (!selected) {
        return [];
      }
      return allDocuments.filter((document) => document.node_id === selected.id);
    }, [allDocuments, selected]);

    const selectedSubtreeDocuments = useMemo<TreeDocumentView[]>(() => {
      if (!selected) {
        return [];
      }
      const subtree = descendantNodeIdsByNode.get(selected.id) ?? new Set([selected.id]);
      const seenDocuments = new Set<string>();
      return allDocuments.filter((document) => {
        if (!subtree.has(document.node_id)) {
          return false;
        }
        if (seenDocuments.has(document.id)) {
          return false;
        }
        seenDocuments.add(document.id);
        return true;
      });
    }, [allDocuments, descendantNodeIdsByNode, selected]);

    const selectedSubtreeOnlyCount = Math.max(0, selectedSubtreeDocuments.length - selectedDirectDocuments.length);

    const selectedDocuments = useMemo<TreeDocumentView[]>(() => {
      if (selectedNode === TREE_INBOX_NODE_ID) {
        return inboxDocuments;
      }
      if (selectedNode === TREE_TEMPLATES_NODE_ID) {
        return templateDocuments;
      }
      if (!selected) {
        return [];
      }
      return includeDescendants ? selectedSubtreeDocuments : selectedDirectDocuments;
    }, [includeDescendants, inboxDocuments, selected, selectedDirectDocuments, selectedNode, selectedSubtreeDocuments, templateDocuments]);

    return (
      <Layout api={api}>
        <section className="panel">
          <PageHeader
            title="트리"
            subtitle={`가상 폴더 스냅샷을 탐색합니다. 현재 뷰: ${selectedView.toUpperCase()}`}
            action={
              <div className="action-row">
                <label className="view-selector" htmlFor="tree-view-select">
                  <span>뷰</span>
                  <select
                    className="field-input"
                    id="tree-view-select"
                    onChange={(event) => {
                      const next = event.target.value as TreeView;
                      setSelectedView(next);
                      saveTreeViewPreference(state.workspaceId, next);
                      setSelectedNode(null);
                    }}
                    value={selectedView}
                  >
                    {TREE_VIEW_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </label>
                <button
                  className="btn btn-secondary"
                  disabled={!state.workspaceId || isRebuilding}
                  onClick={async () => {
                    setTreeError(null);
                    setTreeNotice({ tone: "info", message: "재빌드 요청을 등록하는 중입니다..." });
                    setIsRebuilding(true);
                    try {
                      const rebuild = await withRequestTimeout(
                        api.request<{ snapshot_id: string | null; status: string; pending_count?: number }>(
                          "/tree/rebuild",
                          { method: "POST", body: JSON.stringify({ mode: "DEBOUNCED", view: selectedView }) },
                          true
                        ),
                        REBUILD_REQUEST_TIMEOUT_MS
                      );
                      await refreshTree();
                      const normalized = rebuild.status.trim().toUpperCase();
                      if (normalized === "ACTIVE") {
                        setTreeNotice({ tone: "success", message: "재빌드가 완료되어 활성 트리를 갱신했습니다." });
                      } else if (normalized === "RECOMMENDED") {
                        setTreeNotice({ tone: "info", message: "재빌드가 완료되었습니다. 추천 스냅샷 적용으로 반영하세요." });
                      } else if (normalized === "QUEUED") {
                        const pending = typeof rebuild.pending_count === "number" ? ` (대기 ${rebuild.pending_count}건)` : "";
                        setTreeNotice({ tone: "info", message: `재빌드 요청이 대기열에 등록되었습니다${pending}.` });
                      } else {
                        setTreeNotice({ tone: "success", message: `재빌드가 완료되었습니다 (${rebuild.status}).` });
                      }
                    } catch (e) {
                      if (e instanceof RequestTimeoutError) {
                        setTreeNotice({
                          tone: "info",
                          message: "요청이 길어지고 있습니다. 재빌드는 서버에서 계속 진행 중일 수 있습니다. 잠시 후 새로고침해 확인하세요."
                        });
                      } else {
                        setTreeNotice(null);
                        setTreeError(toUiError(e, "재빌드에 실패했습니다"));
                      }
                    } finally {
                      setIsRebuilding(false);
                    }
                  }}
                  type="button"
                >
                  {isRebuilding ? "재빌드 요청 중..." : "재빌드"}
                </button>
                <button
                  className="btn btn-primary"
                  disabled={!state.workspaceId}
                  onClick={async () => {
                    setTreeError(null);
                    setTreeNotice({ tone: "info", message: "추천 스냅샷을 확인하는 중입니다..." });
                    try {
                      const snaps = await api.request<{ items: Array<{ id: string; status: string }> }>(
                        `/tree/snapshots?view=${selectedView}`,
                        {},
                        true
                      );
                      const recommended = snaps.items.find((item) => item.status === "RECOMMENDED");
                      if (recommended) {
                        await api.request(`/tree/snapshots/${recommended.id}/activate`, { method: "POST", body: "{}" }, true);
                        await refreshTree();
                        setTreeNotice({ tone: "success", message: "추천 스냅샷을 활성화했습니다." });
                      } else {
                        setTreeNotice({ tone: "info", message: "적용할 추천 스냅샷이 없습니다." });
                      }
                    } catch (e) {
                      setTreeNotice(null);
                      setTreeError(toUiError(e, "추천 스냅샷 적용에 실패했습니다"));
                    }
                  }}
                  type="button"
                >
                  추천 스냅샷 적용
                </button>
              </div>
            }
          />

          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <ErrorPanel
            error={treeError}
            onRetry={() => {
              void refreshTree();
            }}
          />
          <NoticePanel notice={treeNotice} />
          {!isTopicView ? <p className="muted">현재 뷰는 탐색 전용입니다. 이동/이름변경 피드백은 Topic 뷰에서 지원됩니다.</p> : null}

          <div className="tree-layout">
            <div className="panel panel-soft">
              <h2 className="section-title">노드</h2>
              <ul className="tree-node-list">
                <li className="tree-node-item tree-node-item-virtual">
                  <button
                    className={`tree-node-button${selectedNode === TREE_INBOX_NODE_ID ? " is-selected" : ""}`}
                    onClick={() => setSelectedNode(TREE_INBOX_NODE_ID)}
                    type="button"
                  >
                    <span className="tree-node-label">Inbox (Unsorted)</span>
                    <span className="tree-node-count">{inboxDocuments.length}</span>
                  </button>
                </li>
                <li className="tree-node-item tree-node-item-virtual">
                  <button
                    className={`tree-node-button${selectedNode === TREE_TEMPLATES_NODE_ID ? " is-selected" : ""}`}
                    onClick={() => setSelectedNode(TREE_TEMPLATES_NODE_ID)}
                    type="button"
                  >
                    <span className="tree-node-label">Templates</span>
                    <span className="tree-node-count">{templateDocuments.length}</span>
                  </button>
                </li>
                {tree?.nodes.map((node) => (
                  <li
                    className="tree-node-item"
                    key={node.id}
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={async () => {
                      if (!draggingDocId || !dragSourceNodeId) {
                        return;
                      }
                      await moveDocument(draggingDocId, dragSourceNodeId, node.id, "DRAG");
                      setDraggingDocId(null);
                      setDragSourceNodeId(null);
                    }}
                  >
                    <button
                      className={`tree-node-button${selectedNode === node.id ? " is-selected" : ""}`}
                      onClick={() => setSelectedNode(node.id)}
                      type="button"
                    >
                      <span className="tree-node-label">{node.parent_id ? `↳ ${node.label}` : node.label}</span>
                      <span className="tree-node-count">{node.documents.length}</span>
                      {node.locked ? <span className="lock-badge">잠금</span> : null}
                    </button>
                    <button
                      className="btn btn-ghost btn-small"
                      disabled={!isTopicView}
                      onClick={async () => {
                        try {
                          await api.request(`/tree/nodes/${node.id}/lock`, { method: "POST", body: JSON.stringify({ locked: !node.locked }) }, true);
                          await refreshTree();
                        } catch (e) {
                          setTreeError(toUiError(e, "잠금 상태 변경에 실패했습니다"));
                        }
                      }}
                      type="button"
                    >
                      {node.locked ? "잠금 해제" : "잠금"}
                    </button>
                  </li>
                ))}
              </ul>
            </div>

            <div className="panel panel-soft">
              <h2 className="section-title">
                {selectedNode === TREE_INBOX_NODE_ID
                  ? "Inbox (Unsorted)"
                  : selectedNode === TREE_TEMPLATES_NODE_ID
                    ? "Templates"
                    : "선택 노드의 문서"}
              </h2>
              {selectedNode === TREE_INBOX_NODE_ID ? <p className="section-subtitle">유보 문서를 빠르게 확정하는 작업함입니다.</p> : null}
              {selectedNode === TREE_TEMPLATES_NODE_ID ? <p className="section-subtitle">템플릿 의심 문서를 모아 검토합니다.</p> : null}
              {selected ? <p className="section-subtitle">노드: {selected.label}</p> : null}
              {selected && selectedSubtreeOnlyCount > 0 ? (
                <label className="tree-subtree-toggle">
                  <input
                    checked={includeDescendants}
                    onChange={(event) => setIncludeDescendants(event.target.checked)}
                    type="checkbox"
                  />
                  하위 노드 문서 포함 보기 (+{selectedSubtreeOnlyCount})
                </label>
              ) : null}

              {selectedDocuments.length === 0 ? (
                <EmptyState
                  title="문서가 없습니다"
                  description={
                    selectedNode === TREE_INBOX_NODE_ID
                      ? "현재 유보된 문서가 없습니다."
                      : selectedNode === TREE_TEMPLATES_NODE_ID
                        ? "현재 템플릿 의심 문서가 없습니다."
                        : "이 노드에 속한 문서가 없습니다."
                  }
                />
              ) : (
                <ul className="tree-doc-list">
                  {selectedDocuments.map((document) => {
                    const readableReason = reasonText(document.quarantine_reason);
                    const quickCandidates = document.placement_candidates.filter((candidate) => candidate.node_id !== document.node_id);
                    const shouldShowCandidates = quickCandidates.length > 0 && (isUnsortedNodeLabel(document.node_label) || Boolean(document.quarantine_reason));
                    return (
                      <li className="tree-doc-item" key={document.id}>
                        <div className="tree-doc-card">
                          <Link
                            className="tree-doc-link"
                            to={state.workspaceId ? workspaceDocumentDetailPath(state.workspaceId, document.id) : `/documents/${document.id}`}
                          >
                            <span className="drag-doc-title">{document.title}</span>
                            {document.title !== document.id ? <span className="drag-doc-id">{document.id}</span> : null}
                          </Link>
                          <div className="tree-doc-meta-row">
                            <span className="score-pill">현재: {document.node_label}</span>
                            {readableReason ? <span className="tree-reason-badge">{readableReason}</span> : null}
                            {typeof document.placement_confidence === "number" ? (
                              <span className="score-pill">신뢰 {Math.round(document.placement_confidence * 100)}%</span>
                            ) : null}
                          </div>
                          {shouldShowCandidates ? (
                            <div className="tree-candidate-row">
                              {quickCandidates.map((candidate) => (
                                <button
                                  className="btn btn-ghost btn-small"
                                  disabled={!isTopicView}
                                  key={`${document.id}-${candidate.node_id}`}
                                  onClick={async () => {
                                    await moveDocument(document.id, document.node_id, candidate.node_id, "QUICK_CONFIRM");
                                    setTreeNotice({
                                      tone: "success",
                                      message: `${document.title} 문서를 '${candidate.label}'로 확정했습니다.`
                                    });
                                  }}
                                  type="button"
                                >
                                  {candidate.label} ({Math.round(candidate.score * 100)}%)
                                </button>
                              ))}
                            </div>
                          ) : null}
                        </div>
                        <button
                          className="btn btn-ghost btn-small"
                          disabled={!isTopicView}
                          draggable={isTopicView}
                          onDragEnd={() => {
                            setDraggingDocId(null);
                            setDragSourceNodeId(null);
                          }}
                          onDragStart={() => {
                            setDraggingDocId(document.id);
                            setDragSourceNodeId(document.node_id);
                          }}
                          type="button"
                        >
                          드래그 이동
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </div>
        </section>

        <section className="panel">
          <div className="two-col-grid">
            <div>
              <h2 className="section-title">이동 피드백</h2>
              <p className="section-subtitle">문서를 현재 선택한 노드로 이동합니다.</p>
              <div className="field-row">
                <input className="field-input field-grow" onChange={(e) => setDocIdForMove(e.target.value)} placeholder="문서 식별자" value={docIdForMove} />
                <button
                  className="btn btn-secondary"
                  disabled={!isTopicView || !selected || !docIdForMove}
                  onClick={async () => {
                    if (!selected || !docIdForMove) {
                      return;
                    }
                    await moveDocument(docIdForMove, null, selected.id, "MANUAL");
                  }}
                  type="button"
                >
                  이동
                </button>
              </div>
            </div>

            <div>
              <h2 className="section-title">노드 이름 변경</h2>
              <p className="section-subtitle">라벨을 수정하고 피드백 서비스와 동기화합니다.</p>
              <div className="field-stack">
                <input className="field-input" onChange={(e) => setRenameNodeId(e.target.value)} placeholder="노드 식별자" value={renameNodeId} />
                <div className="field-row">
                  <input className="field-input field-grow" onChange={(e) => setNewLabel(e.target.value)} placeholder="새 라벨" value={newLabel} />
                  <button
                    className="btn btn-secondary"
                    disabled={!isTopicView || !renameNodeId || !newLabel.trim()}
                    onClick={async () => {
                      if (!renameNodeId || !newLabel.trim()) {
                        return;
                      }

                      const snapshot = tree;
                      if (snapshot) {
                        setTree(renameNodeInTree(snapshot, renameNodeId, newLabel.trim()));
                      }

                      try {
                        await api.request(
                          "/feedback/rename",
                          {
                            method: "POST",
                            body: JSON.stringify({ node_id: renameNodeId, old_label: "", new_label: newLabel.trim() })
                          },
                          true
                        );
                        await refreshTree();
                      } catch (e) {
                        if (snapshot) {
                          setTree(snapshot);
                        }
                        setTreeError(toUiError(e, "이름 변경에 실패했습니다"));
                      }
                    }}
                    type="button"
                  >
                    이름 변경
                  </button>
                </div>
              </div>
            </div>
          </div>
        </section>
      </Layout>
    );
  }

  function WorkspaceRouteSync({ children }: { children: React.ReactNode }) {
    const params = useParams();
    const workspaceId = params.workspaceId ?? null;

    useEffect(() => {
      if (!workspaceId || state.workspaceId === workspaceId) {
        return;
      }
      let active = true;
      void (async () => {
        try {
          const response = await api.request<WorkspaceListResponse>("/workspaces");
          if (!active) {
            return;
          }
          const matched = response.items.find((workspace) => workspace.id === workspaceId);
          setWorkspace(workspaceId, matched?.name ?? workspaceId);
        } catch {
          if (!active) {
            return;
          }
          setWorkspace(workspaceId, state.workspaceName ?? workspaceId);
        }
      })();
      return () => {
        active = false;
      };
    }, [api, setWorkspace, state.workspaceId, state.workspaceName, workspaceId]);

    if (!workspaceId) {
      return <Navigate to="/workspace" replace />;
    }
    if (state.workspaceId !== workspaceId) {
      return (
        <div className="app-shell">
          <section className="panel">
            <p className="muted">워크스페이스 컨텍스트를 동기화하는 중입니다...</p>
          </section>
        </div>
      );
    }
    return <>{children}</>;
  }

  function HomeRedirect() {
    if (!state.accessToken) {
      return <Navigate to="/login" replace />;
    }
    if (state.workspaceId) {
      return <Navigate to={workspaceRootPath(state.workspaceId)} replace />;
    }
    return <Navigate to="/workspace" replace />;
  }

  function LegacyViewRedirect({ view }: { view: Exclude<SidebarViewKey, "workspace"> }) {
    if (!state.workspaceId) {
      return <Navigate to="/workspace" replace />;
    }
    return <Navigate to={view === "documents" ? workspaceRootPath(state.workspaceId) : workspaceViewPath(state.workspaceId, view)} replace />;
  }

  function LegacyDocumentRedirect() {
    const params = useParams();
    if (!params.documentId || !state.workspaceId) {
      return <Navigate to="/workspace" replace />;
    }
    return <Navigate to={workspaceDocumentDetailPath(state.workspaceId, params.documentId)} replace />;
  }

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <HomeRedirect />
          </ProtectedRoute>
        }
      />
      <Route
        path="/workspace"
        element={
          <ProtectedRoute>
            <WorkspacePage />
          </ProtectedRoute>
        }
      />

      <Route
        path="/w/:workspaceId"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <InboxPage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />
      <Route
        path="/w/:workspaceId/doc/:documentId"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <WorkspaceDocumentEditorPage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />
      <Route
        path="/w/:workspaceId/doc/:documentId/details"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <DocumentPage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />
      <Route
        path="/w/:workspaceId/view/documents"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <InboxPage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />
      <Route
        path="/w/:workspaceId/view/tree"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <TreePage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />
      <Route
        path="/w/:workspaceId/view/questions"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <QuestionsPage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />
      <Route
        path="/w/:workspaceId/view/trash"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <TrashPage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />
      <Route
        path="/w/:workspaceId/view/editor"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <EditorPage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />
      <Route
        path="/w/:workspaceId/search"
        element={
          <ProtectedRoute>
            <WorkspaceRouteSync>
              <SearchPage />
            </WorkspaceRouteSync>
          </ProtectedRoute>
        }
      />

      <Route
        path="/inbox"
        element={
          <ProtectedRoute>
            <LegacyViewRedirect view="documents" />
          </ProtectedRoute>
        }
      />
      <Route
        path="/tree"
        element={
          <ProtectedRoute>
            <LegacyViewRedirect view="tree" />
          </ProtectedRoute>
        }
      />
      <Route
        path="/questions"
        element={
          <ProtectedRoute>
            <LegacyViewRedirect view="questions" />
          </ProtectedRoute>
        }
      />
      <Route
        path="/trash"
        element={
          <ProtectedRoute>
            <LegacyViewRedirect view="trash" />
          </ProtectedRoute>
        }
      />
      <Route
        path="/editor"
        element={
          <ProtectedRoute>
            <LegacyViewRedirect view="documents" />
          </ProtectedRoute>
        }
      />
      <Route
        path="/search"
        element={
          <ProtectedRoute>
            <LegacyViewRedirect view="documents" />
          </ProtectedRoute>
        }
      />
      <Route
        path="/documents/:documentId"
        element={
          <ProtectedRoute>
            <LegacyDocumentRedirect />
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
