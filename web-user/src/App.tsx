import { useEffect, useMemo, useState } from "react";
import { Link, Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
import type { Workspace } from "@autodoctree/api-client";
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
type TreeActiveResponse = {
  snapshot_id: string | null;
  status: string;
  nodes: Array<{ id: string; parent_id: string | null; label: string; locked: boolean; documents: string[] }>;
};

type ExplainResponse = {
  document_id: string;
  node_id: string | null;
  rationale: { keywords: string[]; similar_docs: Array<{ document_id: string; title: string; similarity: number }>; signals: string[] };
};

function Layout({ children }: { children: React.ReactNode }) {
  const { state, clearTokens } = useSession();
  const navigate = useNavigate();

  return (
    <div style={{ fontFamily: "sans-serif", maxWidth: 1100, margin: "0 auto", padding: 16 }}>
      <header style={{ display: "flex", gap: 12, alignItems: "center", marginBottom: 16 }}>
        <strong>AutoDoc Tree User</strong>
        <span style={{ background: "#f2f2f2", padding: "4px 8px" }}>
          Workspace: {state.workspaceName ?? "(not selected)"}
        </span>
        <nav style={{ display: "flex", gap: 10 }}>
          <Link to="/workspace">Workspace</Link>
          <Link to="/inbox">Inbox</Link>
          <Link to="/editor">Editor</Link>
          <Link to="/search">Search</Link>
          <Link to="/tree">Tree</Link>
        </nav>
        <button
          onClick={() => {
            clearTokens();
            navigate("/login");
          }}
        >
          Logout
        </button>
      </header>
      {children}
    </div>
  );
}

function LoginPage() {
  const [email, setEmail] = useState("owner@autodoc.local");
  const [password, setPassword] = useState("password");
  const [error, setError] = useState<string | null>(null);
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
    <form
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
          setError(e instanceof Error ? e.message : "login failed");
        }
      }}
      style={{ display: "grid", gap: 8, maxWidth: 420, margin: "80px auto" }}
    >
      <h2>Login</h2>
      <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="Email" />
      <input value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Password" type="password" />
      <button type="submit">Sign in</button>
      {error ? <pre>{error}</pre> : null}
    </form>
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

    useEffect(() => {
      api.request<WorkspaceListResponse>("/workspaces").then((r) => setWorkspaces(r.items));
    }, []);

    return (
      <Layout>
        <h2>Workspace</h2>
        <div style={{ display: "flex", gap: 8 }}>
          <input value={name} onChange={(e) => setName(e.target.value)} />
          <button
            onClick={async () => {
              await api.request("/workspaces", {
                method: "POST",
                body: JSON.stringify({ name })
              });
              const refreshed = await api.request<WorkspaceListResponse>("/workspaces");
              setWorkspaces(refreshed.items);
            }}
          >
            Create Workspace
          </button>
        </div>
        <ul>
          {workspaces.map((ws) => (
            <li key={ws.id}>
              <button onClick={() => setWorkspace(ws.id, ws.name)}>{ws.name}</button> ({ws.role})
            </li>
          ))}
        </ul>
      </Layout>
    );
  }

  function InboxPage() {
    const [documents, setDocuments] = useState<DocumentItem[]>([]);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
      api
        .request<DocumentListResponse>("/documents?page=0&size=20", {}, true)
        .then((r) => setDocuments(r.items))
        .catch((e) => setError(e instanceof Error ? e.message : "failed"));
    }, [state.workspaceId]);

    return (
      <Layout>
        <h2>Inbox</h2>
        {error ? <pre>{error}</pre> : null}
        <ul>
          {documents.map((doc) => (
            <li key={doc.id}>
              <Link to={`/documents/${doc.id}`}>{doc.title}</Link> [{doc.status}] ingest:{doc.pipeline_status.ingest} embed:{doc.pipeline_status.embed} index:
              {doc.pipeline_status.index} tree:{doc.pipeline_status.tree}
            </li>
          ))}
        </ul>
      </Layout>
    );
  }

  function EditorPage() {
    const [title, setTitle] = useState("Draft title");
    const [body, setBody] = useState("# Note\n\ncontent");
    const navigate = useNavigate();

    return (
      <Layout>
        <h2>Editor</h2>
        <div style={{ display: "grid", gap: 8 }}>
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title" />
          <textarea rows={14} value={body} onChange={(e) => setBody(e.target.value)} />
          <button
            onClick={async () => {
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
            }}
          >
            Save Document
          </button>
        </div>
      </Layout>
    );
  }

  function DocumentPage() {
    const params = useParams();
    const [doc, setDoc] = useState<DocumentItem | null>(null);
    const [uploadFile, setUploadFile] = useState<File | null>(null);
    const [explain, setExplain] = useState<ExplainResponse | null>(null);

    useEffect(() => {
      if (!params.documentId) {
        return;
      }
      api.request<DocumentItem>(`/documents/${params.documentId}`, {}, true).then(setDoc);
    }, [params.documentId]);

    return (
      <Layout>
        <h2>Document Detail</h2>
        <pre>{JSON.stringify(doc, null, 2)}</pre>
        <h3>Upload attachment</h3>
        <input type="file" onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)} />
        <button
          disabled={!uploadFile || !params.documentId}
          onClick={async () => {
            if (!uploadFile || !params.documentId) {
              return;
            }
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
            await fetch(presign.upload_url, {
              method: "PUT",
              body: uploadFile,
              headers: {
                "Content-Type": uploadFile.type || "application/octet-stream"
              }
            });
            await api.request(
              "/attachments/complete",
              {
                method: "POST",
                body: JSON.stringify({ attachment_id: presign.attachment_id })
              },
              true
            );
            const refreshed = await api.request<DocumentItem>(`/documents/${params.documentId}`, {}, true);
            setDoc(refreshed);
          }}
        >
          Upload
        </button>
        <h3>Explain</h3>
        <button
          onClick={async () => {
            if (!params.documentId) {
              return;
            }
            const payload = await api.request<ExplainResponse>(`/documents/${params.documentId}/explain`, {}, true);
            setExplain(payload);
          }}
        >
          Load explain
        </button>
        <pre>{JSON.stringify(explain, null, 2)}</pre>
      </Layout>
    );
  }

  function SearchPage() {
    const [q, setQ] = useState("");
    const [results, setResults] = useState<SearchResponse["items"]>([]);

    return (
      <Layout>
        <h2>Search</h2>
        <input value={q} onChange={(e) => setQ(e.target.value)} />
        <button
          onClick={async () => {
            if (!q.trim()) {
              return;
            }
            const response = await api.request<SearchResponse>(`/search?q=${encodeURIComponent(q)}`, {}, true);
            setResults(response.items);
          }}
        >
          Search
        </button>
        <ul>
          {results.map((item) => (
            <li key={item.document_id}>
              <Link to={`/documents/${item.document_id}`}>{item.title}</Link> ({item.score.toFixed(2)})
            </li>
          ))}
        </ul>
      </Layout>
    );
  }

  function TreePage() {
    const [tree, setTree] = useState<TreeActiveResponse | null>(null);
    const [selectedNode, setSelectedNode] = useState<string | null>(null);
    const [docIdForMove, setDocIdForMove] = useState("");
    const [renameNodeId, setRenameNodeId] = useState("");
    const [newLabel, setNewLabel] = useState("");

    useEffect(() => {
      api.request<TreeActiveResponse>("/tree/active", {}, true).then((r) => {
        setTree(r);
        setSelectedNode(r.nodes[0]?.id ?? null);
      });
    }, [state.workspaceId]);

    const selected = tree?.nodes.find((n) => n.id === selectedNode);

    return (
      <Layout>
        <h2>Tree</h2>
        <button
          onClick={async () => {
            await api.request("/tree/rebuild", { method: "POST", body: JSON.stringify({ mode: "DEBOUNCED" }) }, true);
            const refreshed = await api.request<TreeActiveResponse>("/tree/active", {}, true);
            setTree(refreshed);
          }}
        >
          Rebuild
        </button>
        <button
          onClick={async () => {
            const snaps = await api.request<{ items: Array<{ id: string; status: string }> }>("/tree/snapshots", {}, true);
            const recommended = snaps.items.find((item) => item.status === "RECOMMENDED");
            if (recommended) {
              await api.request(`/tree/snapshots/${recommended.id}/activate`, { method: "POST", body: "{}" }, true);
              const refreshed = await api.request<TreeActiveResponse>("/tree/active", {}, true);
              setTree(refreshed);
            }
          }}
        >
          Apply Recommended
        </button>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
          <ul>
            {tree?.nodes.map((node) => (
              <li key={node.id}>
                <button onClick={() => setSelectedNode(node.id)}>
                  {node.parent_id ? "- " : ""}
                  {node.label} {node.locked ? "(locked)" : ""}
                </button>
                <button onClick={() => api.request(`/tree/nodes/${node.id}/lock`, { method: "POST", body: JSON.stringify({ locked: !node.locked }) }, true)}>
                  Toggle lock
                </button>
              </li>
            ))}
          </ul>
          <div>
            <h4>Docs in selected node</h4>
            <pre>{JSON.stringify(selected?.documents ?? [], null, 2)}</pre>
          </div>
        </div>
        <h3>Feedback move</h3>
        <input value={docIdForMove} onChange={(e) => setDocIdForMove(e.target.value)} placeholder="doc id" />
        <button
          onClick={async () => {
            if (!selectedNode || !docIdForMove) {
              return;
            }
            await api.request(
              "/feedback/move",
              {
                method: "POST",
                body: JSON.stringify({
                  document_id: docIdForMove,
                  from_node_id: null,
                  to_node_id: selectedNode
                })
              },
              true
            );
            const refreshed = await api.request<TreeActiveResponse>("/tree/active", {}, true);
            setTree(refreshed);
          }}
        >
          Move
        </button>

        <h3>Rename node</h3>
        <input value={renameNodeId} onChange={(e) => setRenameNodeId(e.target.value)} placeholder="node id" />
        <input value={newLabel} onChange={(e) => setNewLabel(e.target.value)} placeholder="new label" />
        <button
          onClick={async () => {
            await api.request(
              "/feedback/rename",
              {
                method: "POST",
                body: JSON.stringify({ node_id: renameNodeId, old_label: "", new_label: newLabel })
              },
              true
            );
            const refreshed = await api.request<TreeActiveResponse>("/tree/active", {}, true);
            setTree(refreshed);
          }}
        >
          Rename
        </button>
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
