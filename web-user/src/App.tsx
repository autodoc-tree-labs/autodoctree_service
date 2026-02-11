import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, NavLink, Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
import { ApiError, type Workspace } from "@autodoctree/api-client";
import { createApiClient } from "./api";
import { useSession } from "./session";
import type { DocumentItem } from "./types";

type AuthResponse = {
  access_token: string;
  refresh_token: string;
};

type WorkspaceListResponse = { items: Workspace[] };
type DocumentListResponse = { items: DocumentItem[]; page: number; size: number; total: number };
type SearchResponse = { items: Array<{ document_id: string; title: string; score: number }> };
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
const TREE_VIEW_OPTIONS: Array<{ value: TreeView; label: string }> = [
  { value: "topic", label: "Topic" },
  { value: "project", label: "Project" },
  { value: "timeline", label: "Timeline" },
  { value: "version", label: "Version" },
  { value: "template", label: "Template" }
];

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

function Layout({ children }: { children: React.ReactNode }) {
  const { state, clearTokens } = useSession();
  const navigate = useNavigate();

  return (
    <div className="app-shell">
      <header className="panel panel-compact topbar">
        <div className="brand">
          <span className="brand-mark">오</span>
          <div className="brand-text">
            <strong>오토독 트리</strong>
            <span>사용자 콘솔</span>
          </div>
        </div>

        <div className="workspace-chip" title={state.workspaceId ?? undefined}>
          <span>워크스페이스</span>
          <strong>{state.workspaceName ?? "미선택"}</strong>
        </div>

        <nav className="top-nav" aria-label="메인 내비게이션">
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/workspace">
            워크스페이스
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/inbox">
            문서함
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/editor">
            에디터
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/search">
            검색
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/questions">
            질문함
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/tree">
            트리
          </NavLink>
        </nav>

        <button
          className="btn btn-secondary"
          onClick={() => {
            clearTokens();
            navigate("/login");
          }}
          type="button"
        >
          로그아웃
        </button>
      </header>

      <main className="page-stack">{children}</main>
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
          navigate("/workspace");
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

  function WorkspacePage() {
    const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
    const [name, setName] = useState("내 워크스페이스");
    const [error, setError] = useState<UiError | null>(null);

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
      <Layout>
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
                    <button className="workspace-button" onClick={() => setWorkspace(ws.id, ws.name)} type="button">
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

    return (
      <Layout>
        <section className="panel">
          <PageHeader title="문서함" subtitle="최근 수집된 문서와 파이프라인 단계별 진행 상태를 확인하세요." />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadDocuments();
            }}
          />

          {state.workspaceId && documents.length === 0 ? (
            <EmptyState title="문서가 없습니다" description="에디터 탭에서 문서를 작성하거나 업로드하세요." />
          ) : null}

          <div className="doc-grid">
            {documents.map((doc) => (
              <article className="panel panel-soft doc-card" key={doc.id}>
                <div className="doc-card-header">
                  <Link className="doc-link" to={`/documents/${doc.id}`}>
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
              </article>
            ))}
          </div>
        </section>
      </Layout>
    );
  }

  function EditorPage() {
    const [title, setTitle] = useState("초안 제목");
    const [body, setBody] = useState("# 메모\n\n내용");
    const [error, setError] = useState<UiError | null>(null);
    const navigate = useNavigate();

    return (
      <Layout>
        <section className="panel">
          <PageHeader title="에디터" subtitle="마크다운 문서를 작성해 파이프라인으로 전송하세요." />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <ErrorPanel error={error} />

          <div className="field-stack">
            <label className="field-label" htmlFor="editor-title">
              제목
            </label>
            <input className="field-input" id="editor-title" onChange={(e) => setTitle(e.target.value)} placeholder="문서 제목" value={title} />

            <label className="field-label" htmlFor="editor-body">
              본문 (Markdown)
            </label>
            <textarea className="field-textarea editor-textarea" id="editor-body" onChange={(e) => setBody(e.target.value)} rows={14} value={body} />

            <div className="action-row">
              <button
                className="btn btn-primary"
                disabled={!state.workspaceId}
                onClick={async () => {
                  setError(null);
                  try {
                    const created = await api.request<{ id: string }>(
                      "/documents",
                      {
                        method: "POST",
                        body: JSON.stringify({
                          title,
                          body_markdown: body,
                          source_type: "EDITOR"
                        })
                      },
                      true
                    );
                    navigate(`/documents/${created.id}`);
                  } catch (e) {
                    setError(toUiError(e, "문서 저장에 실패했습니다"));
                  }
                }}
                type="button"
              >
                문서 저장
              </button>
            </div>
          </div>
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
      <Layout>
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
                                  <Link className="doc-link" to={`/documents/${neighbor.document_id}`}>
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
                      <Link className="btn btn-secondary" onClick={() => setExplainDrawerOpen(false)} to="/tree">
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
      <Layout>
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
                <Link className="doc-link" to={`/documents/${item.document_id}`}>
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
      <Layout>
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
    const isTopicView = selectedView === "topic";

    useEffect(() => {
      setSelectedView(loadTreeViewPreference(state.workspaceId));
      setSelectedNode(null);
    }, [state.workspaceId]);

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

    const selected = tree?.nodes.find((n) => n.id === selectedNode) ?? null;
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
      return allDocuments.filter((document) => document.node_id === selected.id);
    }, [allDocuments, inboxDocuments, selected, selectedNode, templateDocuments]);

    return (
      <Layout>
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
                  disabled={!state.workspaceId}
                  onClick={async () => {
                    setTreeError(null);
                    setTreeNotice({ tone: "info", message: "재빌드를 실행 중입니다..." });
                    try {
                      const rebuild = await api.request<{ snapshot_id: string | null; status: string; pending_count?: number }>(
                        "/tree/rebuild",
                        { method: "POST", body: JSON.stringify({ mode: "IMMEDIATE", view: selectedView }) },
                        true
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
                      setTreeNotice(null);
                      setTreeError(toUiError(e, "재빌드에 실패했습니다"));
                    }
                  }}
                  type="button"
                >
                  재빌드
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
                          <Link className="tree-doc-link" to={`/documents/${document.id}`}>
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

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/workspace"
        element={
          <ProtectedRoute>
            <WorkspacePage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/inbox"
        element={
          <ProtectedRoute>
            <InboxPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/editor"
        element={
          <ProtectedRoute>
            <EditorPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/documents/:documentId"
        element={
          <ProtectedRoute>
            <DocumentPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/search"
        element={
          <ProtectedRoute>
            <SearchPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/questions"
        element={
          <ProtectedRoute>
            <QuestionsPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/tree"
        element={
          <ProtectedRoute>
            <TreePage />
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
