import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
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

function ErrorPanel({ error, onRetry }: { error: UiError | null; onRetry?: () => void }) {
  if (!error) {
    return null;
  }

  const canRetry = Boolean(onRetry) && Boolean(error.status && error.status >= 500);

  return (
    <div style={{ border: "1px solid #d55", background: "#fff5f5", padding: 10, marginBottom: 10 }}>
      <strong>{error.message}</strong>
      {canRetry ? (
        <button
          style={{ marginLeft: 10 }}
          onClick={() => {
            onRetry?.();
          }}
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
    <div style={{ fontFamily: "sans-serif", maxWidth: 1100, margin: "0 auto", padding: 16 }}>
      <header style={{ display: "flex", gap: 12, alignItems: "center", marginBottom: 16 }}>
        <strong>AutoDoc Tree User</strong>
        <span style={{ background: "#f2f2f2", padding: "4px 8px" }}>Workspace: {state.workspaceName ?? "(not selected)"}</span>
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
          setError(toUiError(e, "login failed"));
        }
      }}
      style={{ display: "grid", gap: 8, maxWidth: 420, margin: "80px auto" }}
    >
      <h2>Login</h2>
      <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="Email" />
      <input value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Password" type="password" />
      <button type="submit">Sign in</button>
      <ErrorPanel error={error} />
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
        <h2>Workspace</h2>
        <ErrorPanel
          error={error}
          onRetry={() => {
            void loadWorkspaces();
          }}
        />
        <div style={{ display: "flex", gap: 8 }}>
          <input value={name} onChange={(e) => setName(e.target.value)} />
          <button
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
        return;
      }
      void loadDocuments();
    }, [loadDocuments, state.workspaceId]);

    return (
      <Layout>
        <h2>Inbox</h2>
        <ErrorPanel
          error={error}
          onRetry={() => {
            void loadDocuments();
          }}
        />
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
    const [error, setError] = useState<UiError | null>(null);
    const navigate = useNavigate();

    return (
      <Layout>
        <h2>Editor</h2>
        <ErrorPanel error={error} />
        <div style={{ display: "grid", gap: 8 }}>
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title" />
          <textarea rows={14} value={body} onChange={(e) => setBody(e.target.value)} />
          <button
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
    const [explainError, setExplainError] = useState<UiError | null>(null);
    const [explainLoading, setExplainLoading] = useState(false);
    const [uploadProgress, setUploadProgress] = useState(0);
    const [uploading, setUploading] = useState(false);
    const [documentError, setDocumentError] = useState<UiError | null>(null);
    const [uploadError, setUploadError] = useState<UiError | null>(null);

    const loadDocument = useCallback(async () => {
      if (!params.documentId) {
        return;
      }
      try {
        const payload = await api.request<DocumentItem>(`/documents/${params.documentId}`, {}, true);
        setDoc(payload);
        setDocumentError(null);
      } catch (e) {
        setDocumentError(toUiError(e, "failed to load document"));
      }
    }, [api, params.documentId]);

    const uploadSelectedFile = useCallback(async () => {
      if (!uploadFile || !params.documentId) {
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
    }, [api, loadDocument, params.documentId, uploadFile]);

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
        <h2>Document Detail</h2>
        <ErrorPanel
          error={documentError}
          onRetry={() => {
            void loadDocument();
          }}
        />

        {doc ? (
          <div style={{ display: "grid", gap: 8 }}>
            <div>Title: {doc.title}</div>
            <div>Status: {doc.status}</div>
            <div>
              Pipeline: ingest={doc.pipeline_status.ingest} embed={doc.pipeline_status.embed} index={doc.pipeline_status.index} tree={doc.pipeline_status.tree}
            </div>
            {doc.pipeline_status.failure_reason ? <div>Failure reason: {doc.pipeline_status.failure_reason}</div> : null}
            <div>Attachments ({doc.attachments.length})</div>
            <ul>
              {doc.attachments.map((attachment) => (
                <li key={attachment.id}>
                  {attachment.id} ({attachment.content_type}, {attachment.size} bytes)
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        <h3>Upload attachment</h3>
        <div
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault();
            const dropped = event.dataTransfer.files?.[0] ?? null;
            setUploadFile(dropped);
          }}
          style={{ border: "1px dashed #999", padding: 12, marginBottom: 8 }}
        >
          Drop file here or select below.
        </div>
        <input type="file" onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)} />
        <div>Selected file: {uploadFile ? `${uploadFile.name} (${uploadFile.size} bytes)` : "none"}</div>
        <div>Upload progress: {uploadProgress}%</div>
        <ErrorPanel
          error={uploadError}
          onRetry={() => {
            void uploadSelectedFile();
          }}
        />
        <div style={{ display: "flex", gap: 8 }}>
          <button disabled={!uploadFile || !params.documentId || uploading} onClick={() => void uploadSelectedFile()}>
            {uploading ? "Uploading..." : "Upload"}
          </button>
          {uploadError ? <button onClick={() => void uploadSelectedFile()}>Retry upload</button> : null}
        </div>

        <h3>Explain</h3>
        <ErrorPanel
          error={explainError}
          onRetry={() => {
            void loadExplain();
          }}
        />
        <button disabled={explainLoading} onClick={() => void loadExplain()}>
          {explainLoading ? "Loading..." : explain ? "Refresh explain" : "Load explain"}
        </button>
        <div style={{ marginTop: 12, border: "1px solid #ddd", padding: 12 }}>
          {explain ? (
            <div style={{ display: "grid", gap: 12 }}>
              {(() => {
                const keywords = explain.rationale?.keywords ?? [];
                const similarDocs = explain.rationale?.similar_docs ?? [];
                const signals = explain.rationale?.signals ?? [];

                return (
                  <>
                    <div>
                      <strong>Keywords</strong>
                      {keywords.length ? (
                        <ul>
                          {keywords.map((keyword) => (
                            <li key={keyword}>{keyword}</li>
                          ))}
                        </ul>
                      ) : (
                        <div>No keywords available.</div>
                      )}
                    </div>
                    <div>
                      <strong>Similar docs</strong>
                      {similarDocs.length ? (
                        <ul>
                          {similarDocs.map((doc) => (
                            <li key={doc.document_id}>
                              <Link to={`/documents/${doc.document_id}`}>{doc.title || doc.document_id}</Link> ({doc.similarity.toFixed(2)})
                            </li>
                          ))}
                        </ul>
                      ) : (
                        <div>No similar docs available.</div>
                      )}
                    </div>
                    <div>
                      <strong>Signals</strong>
                      {signals.length ? (
                        <ul>
                          {signals.map((signal) => (
                            <li key={signal}>{signal}</li>
                          ))}
                        </ul>
                      ) : (
                        <div>No signals available.</div>
                      )}
                    </div>
                  </>
                );
              })()}
            </div>
          ) : (
            <div>Explain data not loaded yet.</div>
          )}
        </div>
      </Layout>
    );
  }

  function SearchPage() {
    const [q, setQ] = useState("");
    const [results, setResults] = useState<SearchResponse["items"]>([]);
    const [error, setError] = useState<UiError | null>(null);

    const executeSearch = useCallback(async () => {
      if (!q.trim()) {
        return;
      }
      setError(null);
      try {
        const response = await api.request<SearchResponse>(`/search?q=${encodeURIComponent(q)}`, {}, true);
        setResults(response.items);
      } catch (e) {
        setError(toUiError(e, "search failed"));
      }
    }, [api, q]);

    return (
      <Layout>
        <h2>Search</h2>
        <ErrorPanel
          error={error}
          onRetry={() => {
            void executeSearch();
          }}
        />
        <input value={q} onChange={(e) => setQ(e.target.value)} />
        <button onClick={() => void executeSearch()}>Search</button>
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
    const [draggingDocId, setDraggingDocId] = useState<string | null>(null);
    const [dragSourceNodeId, setDragSourceNodeId] = useState<string | null>(null);
    const [treeError, setTreeError] = useState<UiError | null>(null);

    const refreshTree = useCallback(async () => {
      try {
        const payload = await api.request<TreeActiveResponse>("/tree/active", {}, true);
        setTree(payload);
        setSelectedNode((prev) => prev ?? payload.nodes[0]?.id ?? null);
        setTreeError(null);
      } catch (e) {
        setTreeError(toUiError(e, "tree load failed"));
      }
    }, [api]);

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
        <h2>Tree</h2>
        <ErrorPanel
          error={treeError}
          onRetry={() => {
            void refreshTree();
          }}
        />
        <button
          onClick={async () => {
            try {
              await api.request("/tree/rebuild", { method: "POST", body: JSON.stringify({ mode: "DEBOUNCED" }) }, true);
              await refreshTree();
            } catch (e) {
              setTreeError(toUiError(e, "rebuild failed"));
            }
          }}
        >
          Rebuild
        </button>
        <button
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
        >
          Apply Recommended
        </button>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
          <ul>
            {tree?.nodes.map((node) => (
              <li
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
                <button onClick={() => setSelectedNode(node.id)}>
                  {node.parent_id ? "- " : ""}
                  {node.label} {node.locked ? "(locked)" : ""}
                </button>
                <button
                  onClick={async () => {
                    try {
                      await api.request(`/tree/nodes/${node.id}/lock`, { method: "POST", body: JSON.stringify({ locked: !node.locked }) }, true);
                      await refreshTree();
                    } catch (e) {
                      setTreeError(toUiError(e, "lock update failed"));
                    }
                  }}
                >
                  Toggle lock
                </button>
              </li>
            ))}
          </ul>
          <div>
            <h4>Docs in selected node</h4>
            <ul>
              {(selected?.documents ?? []).map((documentId) => (
                <li key={documentId}>
                  <button
                    draggable
                    onDragStart={() => {
                      setDraggingDocId(documentId);
                      setDragSourceNodeId(selectedNode);
                    }}
                    onDragEnd={() => {
                      setDraggingDocId(null);
                      setDragSourceNodeId(null);
                    }}
                    style={{ cursor: "grab" }}
                  >
                    Drag: {documentId}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
        <h3>Feedback move</h3>
        <input value={docIdForMove} onChange={(e) => setDocIdForMove(e.target.value)} placeholder="doc id" />
        <button
          onClick={async () => {
            if (!selectedNode || !docIdForMove) {
              return;
            }
            await moveDocument(docIdForMove, null, selectedNode);
          }}
        >
          Move
        </button>

        <h3>Rename node</h3>
        <input value={renameNodeId} onChange={(e) => setRenameNodeId(e.target.value)} placeholder="node id" />
        <input value={newLabel} onChange={(e) => setNewLabel(e.target.value)} placeholder="new label" />
        <button
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
