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

const ROLE_LABEL: Record<string, string> = {
  OWNER: "소유자",
  MEMBER: "멤버",
  VIEWER: "조회자"
};

const STATUS_LABEL: Record<string, string> = {
  DONE: "완료",
  SUCCESS: "성공",
  RUNNING: "진행 중",
  PROCESSING: "처리 중",
  PENDING: "대기",
  FAILED: "실패",
  ERROR: "오류"
};

const STAGE_LABEL: Record<string, string> = {
  INGEST: "수집",
  EXTRACT: "추출",
  SPLIT: "분할",
  CHUNK: "청크",
  EMBED: "임베딩",
  INDEX: "인덱싱",
  TREE: "트리"
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

const roleLabel = (value: string): string => ROLE_LABEL[value.toUpperCase()] ?? value;
const statusLabel = (value: string): string => STATUS_LABEL[value.toUpperCase()] ?? value;
const stageLabel = (value: string): string => STAGE_LABEL[value.toUpperCase()] ?? value;

function Layout({ children }: { children: React.ReactNode }) {
  const { state, clearTokens } = useSession();
  const navigate = useNavigate();

  return (
    <div style={{ fontFamily: "sans-serif", maxWidth: 1100, margin: "0 auto", padding: 16 }}>
      <header style={{ display: "flex", gap: 10, alignItems: "center", marginBottom: 14 }}>
        <strong>오토독 트리 관리자</strong>
        <span style={{ background: "#fee", border: "1px solid #d44", padding: "4px 8px" }}>
          현재 워크스페이스: {state.workspaceName ?? "(없음)"}
        </span>
        <nav style={{ display: "flex", gap: 10 }}>
          <Link to="/workspace">워크스페이스</Link>
          <Link to="/jobs">작업</Link>
          <Link to="/audit">감사 로그</Link>
          <Link to="/members">멤버</Link>
        </nav>
        <button
          onClick={() => {
            clearTokens();
            navigate("/login");
          }}
        >
          로그아웃
        </button>
      </header>
      {children}
    </div>
  );
}

function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
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
          setError(localizeErrorMessage(e instanceof Error ? e.message : "", "로그인에 실패했습니다"));
        }
      }}
      style={{ display: "grid", gap: 8, maxWidth: 400, margin: "80px auto" }}
    >
      <h2>관리자 로그인</h2>
      <input placeholder="이메일 주소" value={email} onChange={(e) => setEmail(e.target.value)} />
      <input placeholder="비밀번호" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
      <button type="submit">로그인</button>
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
        <h2>워크스페이스 전환</h2>
        <ul>
          {workspaces.map((workspace) => (
            <li key={workspace.id}>
              <button onClick={() => setWorkspace(workspace.id, workspace.name)}>{workspace.name}</button> ({roleLabel(workspace.role)})
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
        <h2>작업 콘솔</h2>
        <input value={documentId} onChange={(e) => setDocumentId(e.target.value)} placeholder="문서 식별자" />
        <button onClick={() => void load()}>조회</button>
        <p>재시도 활성화를 위해 워크스페이스 이름을 입력하세요: {state.workspaceName ?? ""}</p>
        <input value={confirmWorkspaceName} onChange={(e) => setConfirmWorkspaceName(e.target.value)} placeholder="워크스페이스 이름 확인 입력" />
        <ul>
          {jobs.map((job) => (
            <li key={job.id}>
              {job.document_id} {stageLabel(job.stage)} {statusLabel(job.status)} 재시도:{job.retries}
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
                재시도
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
        <h2>감사 로그</h2>
        <input value={type} onChange={(e) => setType(e.target.value)} placeholder="액션 타입" />
        <button onClick={() => void load()}>필터</button>
        <pre>{JSON.stringify(items, null, 2)}</pre>
      </Layout>
    );
  }

  function MembersPage() {
    const [members, setMembers] = useState<Member[]>([]);
    const [email, setEmail] = useState("member@autodoc.local");
    const [role, setRole] = useState("MEMBER");
    const [editingRole, setEditingRole] = useState<Record<string, string>>({});

    const load = async () => {
      const response = await api.request<{ items: Member[] }>(`/workspaces/${state.workspaceId}/members`, {}, true);
      setMembers(response.items);
      setEditingRole(
        response.items.reduce<Record<string, string>>((acc, member) => {
          acc[member.user_id] = member.role;
          return acc;
        }, {})
      );
    };

    useEffect(() => {
      if (state.workspaceId) {
        void load();
      }
    }, [state.workspaceId]);

    return (
      <Layout>
        <h2>워크스페이스 멤버</h2>
        <div style={{ display: "flex", gap: 8 }}>
          <input value={email} onChange={(e) => setEmail(e.target.value)} />
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="OWNER">소유자</option>
            <option value="MEMBER">멤버</option>
            <option value="VIEWER">조회자</option>
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
            추가
          </button>
        </div>
        <ul>
          {members.map((member) => (
            <li key={member.user_id}>
              <span>{member.email}</span>
              <span> 현재:{roleLabel(member.role)} </span>
              <select
                value={editingRole[member.user_id] ?? member.role}
                onChange={(e) =>
                  setEditingRole((prev) => ({
                    ...prev,
                    [member.user_id]: e.target.value
                  }))
                }
              >
                <option value="OWNER">소유자</option>
                <option value="MEMBER">멤버</option>
                <option value="VIEWER">조회자</option>
              </select>
              <button
                onClick={async () => {
                  await api.request(
                    `/workspaces/${state.workspaceId}/members/${member.user_id}`,
                    {
                      method: "PATCH",
                      body: JSON.stringify({ role: editingRole[member.user_id] ?? member.role })
                    },
                    true
                  );
                  await load();
                }}
              >
                역할 변경
              </button>
              <button
                onClick={async () => {
                  await api.request(
                    `/workspaces/${state.workspaceId}/members/${member.user_id}`,
                    {
                      method: "DELETE"
                    },
                    true
                  );
                  await load();
                }}
              >
                제거
              </button>
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
