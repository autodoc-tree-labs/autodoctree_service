import { useEffect, useMemo, useState } from "react";
import { Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import type { Workspace } from "@autodoctree/api-client";
import { createApiClient } from "./api";
import { useSession } from "./session";

type AuthResponse = { access_token: string; refresh_token: string };

type WorkspaceListResponse = { items: Workspace[] };
type JobItem = {
  id: string;
  workspace_id: string;
  document_id: string;
  stage: string;
  status: string;
  retries: number;
  created_at: string;
};
type JobsResponse = { items: JobItem[] };
type AuditResponse = {
  items: Array<{
    id: string;
    action: string;
    workspace_id: string;
    actor_user_id: string;
    created_at: string;
    payload: Record<string, unknown>;
  }>;
};

type Member = { user_id: string; email: string; role: string };

function Layout({ children }: { children: React.ReactNode }) {
  const { state, clearTokens } = useSession();
  const navigate = useNavigate();

  return (
    <div style={{ fontFamily: "sans-serif", maxWidth: 1100, margin: "0 auto", padding: 16 }}>
      <header style={{ display: "flex", gap: 10, alignItems: "center", marginBottom: 14 }}>
        <strong>AutoDoc Tree Admin</strong>
        <span style={{ background: "#fee", border: "1px solid #d44", padding: "4px 8px" }}>
          ACTIVE WORKSPACE: {state.workspaceName ?? "(none)"}
        </span>
        <nav style={{ display: "flex", gap: 10 }}>
          <Link to="/workspace">Workspace</Link>
          <Link to="/jobs">Jobs</Link>
          <Link to="/audit">Audit</Link>
          <Link to="/members">Members</Link>
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
      style={{ display: "grid", gap: 8, maxWidth: 400, margin: "80px auto" }}
    >
      <h2>Admin Login</h2>
      <input value={email} onChange={(e) => setEmail(e.target.value)} />
      <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
      <button type="submit">Sign in</button>
      {error ? <pre>{error}</pre> : null}
    </form>
  );
}

function Protected({ children }: { children: React.ReactNode }) {
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
              headers: { "Content-Type": "application/json" },
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

    useEffect(() => {
      api.request<WorkspaceListResponse>("/workspaces").then((r) => setWorkspaces(r.items));
    }, []);

    return (
      <Layout>
        <h2>Workspace Switcher</h2>
        <ul>
          {workspaces.map((workspace) => (
            <li key={workspace.id}>
              <button onClick={() => setWorkspace(workspace.id, workspace.name)}>{workspace.name}</button> ({workspace.role})
            </li>
          ))}
        </ul>
      </Layout>
    );
  }

  function JobsPage() {
    const [jobs, setJobs] = useState<JobItem[]>([]);
    const [documentId, setDocumentId] = useState("");
    const [confirmWorkspaceName, setConfirmWorkspaceName] = useState("");
    const canDangerousAction = confirmWorkspaceName === state.workspaceName;

    const load = async () => {
      const query = documentId ? `?document_id=${encodeURIComponent(documentId)}` : "";
      const response = await api.request<JobsResponse>(`/admin/jobs${query}`, {}, true);
      setJobs(response.items);
    };

    useEffect(() => {
      void load();
    }, [state.workspaceId]);

    return (
      <Layout>
        <h2>Jobs Console</h2>
        <input value={documentId} onChange={(e) => setDocumentId(e.target.value)} placeholder="document id" />
        <button onClick={() => void load()}>Search</button>
        <p>Type workspace name to enable retry: {state.workspaceName ?? ""}</p>
        <input value={confirmWorkspaceName} onChange={(e) => setConfirmWorkspaceName(e.target.value)} placeholder="confirm workspace name" />
        <ul>
          {jobs.map((job) => (
            <li key={job.id}>
              {job.document_id} {job.stage} {job.status} retry:{job.retries}
              <button
                disabled={!canDangerousAction}
                onClick={async () => {
                  await api.request(
                    "/admin/jobs/retry",
                    {
                      method: "POST",
                      body: JSON.stringify({ document_id: job.document_id, stage: job.stage })
                    },
                    true
                  );
                  await load();
                }}
              >
                Retry
              </button>
            </li>
          ))}
        </ul>
      </Layout>
    );
  }

  function AuditPage() {
    const [type, setType] = useState("");
    const [items, setItems] = useState<AuditResponse["items"]>([]);

    const load = async () => {
      const query = type ? `?type=${encodeURIComponent(type)}` : "";
      const response = await api.request<AuditResponse>(`/admin/audit${query}`, {}, true);
      setItems(response.items);
    };

    useEffect(() => {
      void load();
    }, [state.workspaceId]);

    return (
      <Layout>
        <h2>Audit Logs</h2>
        <input value={type} onChange={(e) => setType(e.target.value)} placeholder="action type" />
        <button onClick={() => void load()}>Filter</button>
        <pre>{JSON.stringify(items, null, 2)}</pre>
      </Layout>
    );
  }

  function MembersPage() {
    const [members, setMembers] = useState<Member[]>([]);
    const [email, setEmail] = useState("member@autodoc.local");
    const [role, setRole] = useState("MEMBER");

    const load = async () => {
      const response = await api.request<{ items: Member[] }>(`/workspaces/${state.workspaceId}/members`, {}, true);
      setMembers(response.items);
    };

    useEffect(() => {
      if (state.workspaceId) {
        void load();
      }
    }, [state.workspaceId]);

    return (
      <Layout>
        <h2>Workspace Members</h2>
        <div style={{ display: "flex", gap: 8 }}>
          <input value={email} onChange={(e) => setEmail(e.target.value)} />
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            <option>OWNER</option>
            <option>MEMBER</option>
            <option>VIEWER</option>
          </select>
          <button
            onClick={async () => {
              await api.request(
                `/workspaces/${state.workspaceId}/members`,
                {
                  method: "POST",
                  body: JSON.stringify({ email, role })
                },
                true
              );
              await load();
            }}
          >
            Add
          </button>
        </div>
        <ul>
          {members.map((member) => (
            <li key={member.user_id}>
              {member.email} ({member.role})
            </li>
          ))}
        </ul>
      </Layout>
    );
  }

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/workspace"
        element={
          <Protected>
            <WorkspacePage />
          </Protected>
        }
      />
      <Route
        path="/jobs"
        element={
          <Protected>
            <JobsPage />
          </Protected>
        }
      />
      <Route
        path="/audit"
        element={
          <Protected>
            <AuditPage />
          </Protected>
        }
      />
      <Route
        path="/members"
        element={
          <Protected>
            <MembersPage />
          </Protected>
        }
      />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
