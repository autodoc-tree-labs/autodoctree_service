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
type TreeNode = { id: string; parent_id: string | null; label: string; locked: boolean; documents: string[] };
type TreeActiveResponse = {
  snapshot_id: string | null;
  status: string;
  nodes: TreeNode[];
};

type ExplainResponse = {
  document_id: string;
  node_id: string | null;
  rationale?: {
    keywords?: string[];
    similar_docs?: Array<{ document_id: string; title: string; similarity: number }>;
    signals?: string[];
  };
};

type UiError = {
  message: string;
  status: number | null;
};

type StatusTone = "neutral" | "good" | "warn" | "bad";

const toUiError = (error: unknown, fallback: string): UiError => {
  if (error instanceof ApiError) {
    return {
      message: error.message,
      status: error.status
    };
  }
  if (error instanceof Error) {
    return {
      message: error.message || fallback,
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

function StatusChip({ label, value }: { label: string; value: string }) {
  return <span className={`status-chip status-chip-${statusTone(value)}`}>{label}: {value}</span>;
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
      title="Select a workspace first"
      description="Tenant-scoped pages need an active workspace to attach the X-Workspace-Id header."
      action={<NavLink className="btn btn-primary" to="/workspace">Go to workspace</NavLink>}
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
        {error.status ? <span className="error-code">HTTP {error.status}</span> : null}
      </div>
      {canRetry ? (
        <button
          className="btn btn-ghost btn-small"
          onClick={() => {
            onRetry?.();
          }}
          type="button"
        >
          Retry
        </button>
      ) : null}
    </div>
  );
}

const moveDocumentInTree = (tree: TreeActiveResponse, documentId: string, fromNodeId: string | null, toNodeId: string): TreeActiveResponse => {
  return {
    ...tree,
    nodes: tree.nodes.map((node) => {
      const removeFromNode = fromNodeId ? node.id === fromNodeId : true;
      const withoutDoc = removeFromNode ? node.documents.filter((docId) => docId !== documentId) : node.documents;
      if (node.id === toNodeId && !withoutDoc.includes(documentId)) {
        return { ...node, documents: [...withoutDoc, documentId] };
      }
      return { ...node, documents: withoutDoc };
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
          <span className="brand-mark">A</span>
          <div className="brand-text">
            <strong>AutoDoc Tree</strong>
            <span>User Console</span>
          </div>
        </div>

        <div className="workspace-chip" title={state.workspaceId ?? undefined}>
          <span>Workspace</span>
          <strong>{state.workspaceName ?? "Not selected"}</strong>
        </div>

        <nav className="top-nav" aria-label="Main navigation">
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/workspace">
            Workspace
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/inbox">
            Inbox
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/editor">
            Editor
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/search">
            Search
          </NavLink>
          <NavLink className={({ isActive }) => `top-nav-link${isActive ? " is-active" : ""}`} to="/tree">
            Tree
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
          Logout
        </button>
      </header>

      <main className="page-stack">{children}</main>
    </div>
  );
}

function LoginPage() {
  const [email, setEmail] = useState("owner@autodoc.local");
  const [password, setPassword] = useState("password");
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
            setError(toUiError(e, "login failed"));
          }
        }}
      >
        <div className="auth-eyebrow">AutoDoc Tree</div>
        <h1 className="auth-title">Welcome back</h1>
        <p className="auth-subtitle">Sign in with your workspace account to organize documents automatically.</p>

        <label className="field-label" htmlFor="login-email">
          Email
        </label>
        <input
          className="field-input"
          id="login-email"
          onChange={(e) => setEmail(e.target.value)}
          placeholder="owner@autodoc.local"
          type="email"
          value={email}
        />

        <label className="field-label" htmlFor="login-password">
          Password
        </label>
        <input
          className="field-input"
          id="login-password"
          onChange={(e) => setPassword(e.target.value)}
          placeholder="password"
          type="password"
          value={password}
        />

        <button className="btn btn-primary btn-block" type="submit">
          Sign in
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
    const [name, setName] = useState("My Workspace");
    const [error, setError] = useState<UiError | null>(null);

    const loadWorkspaces = useCallback(async () => {
      setError(null);
      try {
        const response = await api.request<WorkspaceListResponse>("/workspaces");
        setWorkspaces(response.items);
      } catch (e) {
        setError(toUiError(e, "failed to load workspaces"));
      }
    }, [api]);

    useEffect(() => {
      void loadWorkspaces();
    }, [loadWorkspaces]);

    return (
      <Layout>
        <section className="panel">
          <PageHeader title="Workspace" subtitle="Choose the tenant context before browsing or editing documents." />
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadWorkspaces();
            }}
          />

          <div className="field-row">
            <div className="field-grow">
              <label className="field-label" htmlFor="workspace-name">
                New workspace name
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
                  setError(toUiError(e, "failed to create workspace"));
                }
              }}
              type="button"
            >
              Create workspace
            </button>
          </div>

          {workspaces.length === 0 ? (
            <EmptyState title="No workspace yet" description="Create one to start organizing documents." />
          ) : (
            <ul className="workspace-list">
              {workspaces.map((ws) => {
                const roleName = ws.role.toLowerCase();
                const isSelected = state.workspaceId === ws.id;
                return (
                  <li className={`workspace-item${isSelected ? " is-selected" : ""}`} key={ws.id}>
                    <button className="workspace-button" onClick={() => setWorkspace(ws.id, ws.name)} type="button">
                      <span className="workspace-name">{ws.name}</span>
                      <span className={`role-badge role-${roleName}`}>{ws.role}</span>
                      {isSelected ? <span className="workspace-current">Current</span> : null}
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
        setError(toUiError(e, "failed to load documents"));
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
          <PageHeader title="Inbox" subtitle="Recently ingested documents and pipeline progress by stage." />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <ErrorPanel
            error={error}
            onRetry={() => {
              void loadDocuments();
            }}
          />

          {state.workspaceId && documents.length === 0 ? (
            <EmptyState title="No documents yet" description="Create or upload a document from the Editor tab." />
          ) : null}

          <div className="doc-grid">
            {documents.map((doc) => (
              <article className="panel panel-soft doc-card" key={doc.id}>
                <div className="doc-card-header">
                  <Link className="doc-link" to={`/documents/${doc.id}`}>
                    {doc.title}
                  </Link>
                  <StatusChip label="Status" value={doc.status} />
                </div>
                <div className="pipeline-grid">
                  <StatusChip label="Ingest" value={doc.pipeline_status.ingest} />
                  <StatusChip label="Embed" value={doc.pipeline_status.embed} />
                  <StatusChip label="Index" value={doc.pipeline_status.index} />
                  <StatusChip label="Tree" value={doc.pipeline_status.tree} />
                </div>
              </article>
            ))}
          </div>
        </section>
      </Layout>
    );
  }

  function EditorPage() {
    const [title, setTitle] = useState("Draft title");
    const [body, setBody] = useState("# Note\n\ncontent");
    const [error, setError] = useState<UiError | null>(null);
    const navigate = useNavigate();

    return (
      <Layout>
        <section className="panel">
          <PageHeader title="Editor" subtitle="Create markdown docs and push them into the processing pipeline." />
          {!state.workspaceId ? <WorkspaceRequiredHint /> : null}
          <ErrorPanel error={error} />

          <div className="field-stack">
            <label className="field-label" htmlFor="editor-title">
              Title
            </label>
            <input className="field-input" id="editor-title" onChange={(e) => setTitle(e.target.value)} placeholder="Document title" value={title} />

            <label className="field-label" htmlFor="editor-body">
              Body (Markdown)
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
                    setError(toUiError(e, "failed to save document"));
                  }
                }}
                type="button"
              >
                Save document
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
    const [explainLoading, setExplainLoading] = useState(false);
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
        setDocumentError(toUiError(e, "failed to load document"));
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
          xhr.onerror = () => reject(new Error("upload failed"));
          xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
              setUploadProgress(100);
              resolve();
            } else {
              reject(new Error(`upload failed: ${xhr.status}`));
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
        setUploadError(toUiError(e, "upload failed"));
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
      } catch (e) {
        setExplainError(toUiError(e, "failed to load explain"));
      } finally {
        setExplainLoading(false);
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

    return (
      <Layout>
        <section className="panel">
          <PageHeader
            title={doc?.title ?? "Document detail"}
            subtitle="Inspect processing stages, attachments, and explain rationale for placement."
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
                <span className="meta-label">Document ID</span>
                <code>{doc.id}</code>
              </div>
              <div className="meta-block">
                <span className="meta-label">Status</span>
                <StatusChip label="Doc" value={doc.status} />
              </div>
              <div className="meta-block meta-block-wide">
                <span className="meta-label">Pipeline</span>
                <div className="pipeline-grid">
                  <StatusChip label="Ingest" value={doc.pipeline_status.ingest} />
                  <StatusChip label="Embed" value={doc.pipeline_status.embed} />
                  <StatusChip label="Index" value={doc.pipeline_status.index} />
                  <StatusChip label="Tree" value={doc.pipeline_status.tree} />
                </div>
              </div>
              {doc.pipeline_status.failure_reason ? (
                <div className="meta-block meta-block-wide meta-warning">
                  <span className="meta-label">Failure reason</span>
                  <span>{doc.pipeline_status.failure_reason}</span>
                </div>
              ) : null}
              <div className="meta-block meta-block-wide">
                <span className="meta-label">Attachments ({doc.attachments.length})</span>
                {doc.attachments.length === 0 ? (
                  <span className="muted">No attachments yet.</span>
                ) : (
                  <ul className="simple-list">
                    {doc.attachments.map((attachment) => (
                      <li key={attachment.id}>
                        <code>{attachment.id}</code> ({attachment.content_type}, {attachment.size} bytes)
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          ) : (
            <p className="muted">Loading document...</p>
          )}
        </section>

        <section className="panel">
          <h2 className="section-title">Attachment upload</h2>
          <p className="section-subtitle">Drag a file or choose one to attach to this document.</p>

          <div
            className="dropzone"
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => {
              event.preventDefault();
              const dropped = event.dataTransfer.files?.[0] ?? null;
              setUploadFile(dropped);
            }}
          >
            Drop file here
          </div>

          <input className="field-input" onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)} type="file" />
          <p className="muted">Selected: {uploadFile ? `${uploadFile.name} (${uploadFile.size} bytes)` : "none"}</p>

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
              {uploading ? "Uploading..." : "Upload"}
            </button>
            {uploadError ? (
              <button className="btn btn-secondary" onClick={() => void uploadSelectedFile()} type="button">
                Retry upload
              </button>
            ) : null}
          </div>
        </section>

        <section className="panel">
          <div className="section-header-inline">
            <div>
              <h2 className="section-title">Explain</h2>
              <p className="section-subtitle">See why the document is placed into a specific tree node.</p>
            </div>
            <button
              className="btn btn-secondary"
              disabled={explainLoading}
              onClick={() => void loadExplain()}
              type="button"
            >
              {explainLoading ? "Loading..." : explain ? "Refresh explain" : "Load explain"}
            </button>
          </div>
          <ErrorPanel
            error={explainError}
            onRetry={() => {
              void loadExplain();
            }}
          />

          {explain ? (
            <div className="explain-grid">
              <div className="explain-block">
                <h3>Keywords</h3>
                {(explain.rationale?.keywords ?? []).length ? (
                  <ul className="simple-list">
                    {(explain.rationale?.keywords ?? []).map((keyword) => (
                      <li key={keyword}>{keyword}</li>
                    ))}
                  </ul>
                ) : (
                  <p className="muted">No keywords available.</p>
                )}
              </div>

              <div className="explain-block">
                <h3>Similar docs</h3>
                {(explain.rationale?.similar_docs ?? []).length ? (
                  <ul className="simple-list">
                    {(explain.rationale?.similar_docs ?? []).map((related) => (
                      <li key={related.document_id}>
                        <Link className="doc-link" to={`/documents/${related.document_id}`}>
                          {related.title || related.document_id}
                        </Link>{" "}
                        ({related.similarity.toFixed(2)})
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="muted">No similar docs available.</p>
                )}
              </div>

              <div className="explain-block">
                <h3>Signals</h3>
                {(explain.rationale?.signals ?? []).length ? (
                  <ul className="simple-list">
                    {(explain.rationale?.signals ?? []).map((signal) => (
                      <li key={signal}>{signal}</li>
                    ))}
                  </ul>
                ) : (
                  <p className="muted">No signals available.</p>
                )}
              </div>
            </div>
          ) : (
            <p className="muted">Explain data not loaded yet.</p>
          )}
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
        setError(toUiError(e, "search failed"));
      }
    }, [api, q, state.workspaceId]);

    return (
      <Layout>
        <section className="panel">
          <PageHeader title="Search" subtitle="BM25 search over workspace-scoped indexed content." />
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
            <input className="field-input" onChange={(e) => setQ(e.target.value)} placeholder="Search documents" value={q} />
            <button className="btn btn-primary" disabled={!state.workspaceId || !q.trim()} type="submit">
              Search
            </button>
          </form>

          {state.workspaceId && results.length === 0 && q.trim() ? (
            <EmptyState title="No matches" description="Try broader keywords or check whether indexing is complete." />
          ) : null}

          <ul className="search-results">
            {results.map((item) => (
              <li className="search-result-item" key={item.document_id}>
                <Link className="doc-link" to={`/documents/${item.document_id}`}>
                  {item.title}
                </Link>
                <span className="score-pill">Score {item.score.toFixed(2)}</span>
              </li>
            ))}
          </ul>
        </section>
      </Layout>
    );
  }

  function TreePage() {
    const [tree, setTree] = useState<TreeActiveResponse | null>(null);
    const [selectedNode, setSelectedNode] = useState<string | null>(null);
    const [docIdForMove, setDocIdForMove] = useState("");
    const [renameNodeId, setRenameNodeId] = useState("");
    const [newLabel, setNewLabel] = useState("");
    const [draggingDocId, setDraggingDocId] = useState<string | null>(null);
    const [dragSourceNodeId, setDragSourceNodeId] = useState<string | null>(null);
    const [treeError, setTreeError] = useState<UiError | null>(null);

    const refreshTree = useCallback(async () => {
      if (!state.workspaceId) {
        setTree(null);
        setSelectedNode(null);
        return;
      }

      try {
        const payload = await api.request<TreeActiveResponse>("/tree/active", {}, true);
        setTree(payload);
        setSelectedNode((prev) => prev ?? payload.nodes[0]?.id ?? null);
        setTreeError(null);
      } catch (e) {
        setTreeError(toUiError(e, "tree load failed"));
      }
    }, [api, state.workspaceId]);

    const moveDocument = useCallback(
      async (documentId: string, fromNodeId: string | null, toNodeId: string) => {
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
                to_node_id: toNodeId
              })
            },
            true
          );
          await refreshTree();
        } catch (e) {
          setTree(snapshot);
          setTreeError(toUiError(e, "move failed"));
        }
      },
      [api, refreshTree, tree]
    );

    useEffect(() => {
      void refreshTree();
    }, [refreshTree, state.workspaceId]);

    const selected = tree?.nodes.find((n) => n.id === selectedNode);

    return (
      <Layout>
        <section className="panel">
          <PageHeader
            title="Tree"
            subtitle="Browse virtual folder snapshots, lock nodes, and submit move/rename feedback."
            action={
              <div className="action-row">
                <button
                  className="btn btn-secondary"
                  disabled={!state.workspaceId}
                  onClick={async () => {
                    try {
                      await api.request("/tree/rebuild", { method: "POST", body: JSON.stringify({ mode: "DEBOUNCED" }) }, true);
                      await refreshTree();
                    } catch (e) {
                      setTreeError(toUiError(e, "rebuild failed"));
                    }
                  }}
                  type="button"
                >
                  Rebuild
                </button>
                <button
                  className="btn btn-primary"
                  disabled={!state.workspaceId}
                  onClick={async () => {
                    try {
                      const snaps = await api.request<{ items: Array<{ id: string; status: string }> }>("/tree/snapshots", {}, true);
                      const recommended = snaps.items.find((item) => item.status === "RECOMMENDED");
                      if (recommended) {
                        await api.request(`/tree/snapshots/${recommended.id}/activate`, { method: "POST", body: "{}" }, true);
                        await refreshTree();
                      }
                    } catch (e) {
                      setTreeError(toUiError(e, "apply recommended failed"));
                    }
                  }}
                  type="button"
                >
                  Apply recommended
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

          <div className="tree-layout">
            <div className="panel panel-soft">
              <h2 className="section-title">Nodes</h2>
              <ul className="tree-node-list">
                {tree?.nodes.map((node) => (
                  <li
                    className="tree-node-item"
                    key={node.id}
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={async () => {
                      if (!draggingDocId) {
                        return;
                      }
                      await moveDocument(draggingDocId, dragSourceNodeId, node.id);
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
                      {node.locked ? <span className="lock-badge">Locked</span> : null}
                    </button>
                    <button
                      className="btn btn-ghost btn-small"
                      onClick={async () => {
                        try {
                          await api.request(`/tree/nodes/${node.id}/lock`, { method: "POST", body: JSON.stringify({ locked: !node.locked }) }, true);
                          await refreshTree();
                        } catch (e) {
                          setTreeError(toUiError(e, "lock update failed"));
                        }
                      }}
                      type="button"
                    >
                      {node.locked ? "Unlock" : "Lock"}
                    </button>
                  </li>
                ))}
              </ul>
            </div>

            <div className="panel panel-soft">
              <h2 className="section-title">Docs in selected node</h2>
              {selected ? <p className="section-subtitle">Node: {selected.label}</p> : null}
              <ul className="simple-list">
                {(selected?.documents ?? []).map((documentId) => (
                  <li key={documentId}>
                    <button
                      className="btn btn-ghost drag-doc-btn"
                      draggable
                      onDragEnd={() => {
                        setDraggingDocId(null);
                        setDragSourceNodeId(null);
                      }}
                      onDragStart={() => {
                        setDraggingDocId(documentId);
                        setDragSourceNodeId(selectedNode);
                      }}
                      type="button"
                    >
                      Drag {documentId}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </section>

        <section className="panel">
          <div className="two-col-grid">
            <div>
              <h2 className="section-title">Feedback move</h2>
              <p className="section-subtitle">Move a document to the currently selected node.</p>
              <div className="field-row">
                <input className="field-input field-grow" onChange={(e) => setDocIdForMove(e.target.value)} placeholder="document id" value={docIdForMove} />
                <button
                  className="btn btn-secondary"
                  disabled={!selectedNode || !docIdForMove}
                  onClick={async () => {
                    if (!selectedNode || !docIdForMove) {
                      return;
                    }
                    await moveDocument(docIdForMove, null, selectedNode);
                  }}
                  type="button"
                >
                  Move
                </button>
              </div>
            </div>

            <div>
              <h2 className="section-title">Rename node</h2>
              <p className="section-subtitle">Update label text and sync to feedback API.</p>
              <div className="field-stack">
                <input className="field-input" onChange={(e) => setRenameNodeId(e.target.value)} placeholder="node id" value={renameNodeId} />
                <div className="field-row">
                  <input className="field-input field-grow" onChange={(e) => setNewLabel(e.target.value)} placeholder="new label" value={newLabel} />
                  <button
                    className="btn btn-secondary"
                    disabled={!renameNodeId || !newLabel.trim()}
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
                        setTreeError(toUiError(e, "rename failed"));
                      }
                    }}
                    type="button"
                  >
                    Rename
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
