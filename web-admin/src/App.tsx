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
type MaskedText = {
  hash: string;
  length: number;
};
type DebugDocNeighborItem = {
  neighbor_doc_id: string;
  title_mask: MaskedText;
  channel_scores: {
    semantic: number | null;
    lexical: number;
    final: number;
  };
  edge_decision: {
    lexical_gate_passed: boolean;
    reason: string;
    entity_overlap: number;
    title_overlap: number;
  };
};
type DebugDocumentResponse = {
  document_id: string;
  title_mask: MaskedText;
  assignment: {
    node_id: string | null;
    node_label: string | null;
    snapshot_id: string | null;
  };
  assignment_confidence: number;
  neighbors: DebugDocNeighborItem[];
  trace_id: string | null;
  request_id: string | null;
};
type DebugClusterResponse = {
  cluster_id: string;
  snapshot_id: string;
  label: string;
  member_count: number;
  members: Array<{
    document_id: string;
    title_mask: MaskedText;
    signals: string[];
  }>;
  exemplars: Array<{
    document_id: string;
    title_mask: MaskedText;
    avg_similarity: number;
  }>;
  label_candidates: string[];
  trace_id: string | null;
  request_id: string | null;
};
type DebugRebuildResponse = {
  snapshot_id: string;
  status: string;
  created_at: string;
  parameters: Record<string, unknown>;
  models: Record<string, unknown>;
  decision_summary: Record<string, unknown>;
  cluster_count: number;
  membership_count: number;
  unsorted_ratio: number;
  stage_logs: Array<{
    stage: string;
    duration_ms: number;
    details: Record<string, unknown>;
  }>;
  trace_id: string | null;
  request_id: string | null;
};
type ClusterStatsResponse = {
  snapshot_id: string | null;
  status: string;
  cluster_count: number;
  avg_cluster_size: number;
  neighbor_edges_total: number;
  edges_filtered_total: number;
  label_filtered_total: number;
  avg_label_length: number;
  tree_rebuild_duration_ms: number;
  auto_ratio: number;
  recommend_ratio: number;
  moved_ratio: number;
  churn_ratio: number;
};
type TreePolicyResponse = {
  workspace_id: string;
  auto_threshold: number;
  recommend_threshold: number;
  quarantine_enabled: boolean;
  reranker_enabled: boolean;
  source: string;
  updated_by: string | null;
  updated_at: string | null;
};
type TreeActiveResponse = {
  snapshot_id: string | null;
  status: string;
  nodes: Array<{
    id: string;
    parent_id: string | null;
    label: string;
    locked: boolean;
  }>;
};
type UserRuleItem = {
  id: string;
  rule_type: string;
  rule_value: string;
  rule_effect: string;
  node_id: string;
  node_label: string | null;
  enabled: boolean;
  created_at: string;
};
type UserRuleListResponse = {
  items: UserRuleItem[];
};
type UserRuleMutationResponse = {
  id: string;
  rule_type: string;
  rule_value: string;
  rule_effect: string;
  node_id: string;
};
type UserRulePreviewResponse = {
  document_id: string;
  rule_type: string;
  rule_value: string;
  rule_effect: string;
  matched: boolean;
  target_node_id: string | null;
  target_node_label: string | null;
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
const formatMetric = (value: number | null | undefined, digits = 3): string => {
  if (value == null || Number.isNaN(value)) {
    return "-";
  }
  return value.toFixed(digits);
};

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
          <Link to="/tree-debug">트리 디버그</Link>
          <Link to="/tree-policy">정책 설정</Link>
          <Link to="/tree-rules">규칙 관리</Link>
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

  function TreeDebugPage() {
    const [documentId, setDocumentId] = useState("");
    const [clusterId, setClusterId] = useState("");
    const [snapshotId, setSnapshotId] = useState("");
    const [documentDebug, setDocumentDebug] = useState<DebugDocumentResponse | null>(null);
    const [clusterDebug, setClusterDebug] = useState<DebugClusterResponse | null>(null);
    const [rebuildDebug, setRebuildDebug] = useState<DebugRebuildResponse | null>(null);
    const [stats, setStats] = useState<ClusterStatsResponse | null>(null);
    const [error, setError] = useState<string | null>(null);

    const loadStats = async () => {
      try {
        const response = await api.request<ClusterStatsResponse>("/admin/tree/debug/cluster-stats", {}, true);
        setStats(response);
      } catch (e) {
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "클러스터 통계를 불러오지 못했습니다."));
      }
    };

    useEffect(() => {
      if (!state.workspaceId) {
        setStats(null);
        setDocumentDebug(null);
        setClusterDebug(null);
        setRebuildDebug(null);
        return;
      }
      void loadStats();
    }, [state.workspaceId]);

    const loadDocumentDebug = async () => {
      if (!state.workspaceId) {
        setError("먼저 워크스페이스를 선택하세요.");
        return;
      }
      if (!documentId.trim()) {
        setError("문서 식별자를 입력하세요.");
        return;
      }
      setError(null);
      try {
        const response = await api.request<DebugDocumentResponse>(`/admin/tree/debug/docs/${encodeURIComponent(documentId.trim())}?top_n=8`, {}, true);
        setDocumentDebug(response);
        if (response.assignment.node_id) {
          setClusterId(response.assignment.node_id);
        }
        if (response.assignment.snapshot_id) {
          setSnapshotId(response.assignment.snapshot_id);
        }
      } catch (e) {
        setDocumentDebug(null);
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "문서 디버그 정보를 불러오지 못했습니다."));
      }
    };

    const loadClusterDebug = async () => {
      if (!state.workspaceId) {
        setError("먼저 워크스페이스를 선택하세요.");
        return;
      }
      if (!clusterId.trim()) {
        setError("클러스터 식별자를 입력하세요.");
        return;
      }
      setError(null);
      try {
        const response = await api.request<DebugClusterResponse>(`/admin/tree/debug/clusters/${encodeURIComponent(clusterId.trim())}`, {}, true);
        setClusterDebug(response);
        if (response.snapshot_id) {
          setSnapshotId(response.snapshot_id);
        }
      } catch (e) {
        setClusterDebug(null);
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "클러스터 디버그 정보를 불러오지 못했습니다."));
      }
    };

    const loadRebuildDebug = async () => {
      if (!state.workspaceId) {
        setError("먼저 워크스페이스를 선택하세요.");
        return;
      }
      if (!snapshotId.trim()) {
        setError("스냅샷 식별자를 입력하세요.");
        return;
      }
      setError(null);
      try {
        const response = await api.request<DebugRebuildResponse>(`/admin/tree/debug/rebuilds/${encodeURIComponent(snapshotId.trim())}`, {}, true);
        setRebuildDebug(response);
      } catch (e) {
        setRebuildDebug(null);
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "리빌드 디버그 정보를 불러오지 못했습니다."));
      }
    };

    return (
      <Layout>
        <h2>트리 디버그</h2>
        <p>문서/클러스터/리빌드 단위로 분해된 원인 정보를 확인합니다.</p>
        <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
          <button onClick={() => void loadStats()}>통계 새로고침</button>
        </div>
        {error ? (
          <div style={{ border: "1px solid #d33", padding: 10, marginBottom: 12, background: "#fff3f3" }}>{error}</div>
        ) : null}

        <section style={{ border: "1px solid #ccd", padding: 12, marginBottom: 14 }}>
          <h3 style={{ marginTop: 0 }}>클러스터 통계</h3>
          {stats ? (
            <table>
              <tbody>
                <tr>
                  <td>스냅샷</td>
                  <td>{stats.snapshot_id ?? "없음"}</td>
                </tr>
                <tr>
                  <td>상태</td>
                  <td>{stats.status}</td>
                </tr>
                <tr>
                  <td>클러스터 수</td>
                  <td>{stats.cluster_count}</td>
                </tr>
                <tr>
                  <td>평균 클러스터 크기</td>
                  <td>{formatMetric(stats.avg_cluster_size, 2)}</td>
                </tr>
                <tr>
                  <td>neighbor_edges_total</td>
                  <td>{formatMetric(stats.neighbor_edges_total, 0)}</td>
                </tr>
                <tr>
                  <td>edges_filtered_total</td>
                  <td>{formatMetric(stats.edges_filtered_total, 0)}</td>
                </tr>
                <tr>
                  <td>label_filtered_total</td>
                  <td>{formatMetric(stats.label_filtered_total, 0)}</td>
                </tr>
                <tr>
                  <td>avg_label_length</td>
                  <td>{formatMetric(stats.avg_label_length, 2)}</td>
                </tr>
                <tr>
                  <td>tree_rebuild_duration_ms</td>
                  <td>{formatMetric(stats.tree_rebuild_duration_ms, 2)}</td>
                </tr>
                <tr>
                  <td>moved_ratio</td>
                  <td>{formatMetric(stats.moved_ratio, 3)}</td>
                </tr>
                <tr>
                  <td>churn_ratio</td>
                  <td>{formatMetric(stats.churn_ratio, 3)}</td>
                </tr>
                <tr>
                  <td>auto_ratio</td>
                  <td>{formatMetric(stats.auto_ratio, 3)}</td>
                </tr>
                <tr>
                  <td>recommend_ratio</td>
                  <td>{formatMetric(stats.recommend_ratio, 3)}</td>
                </tr>
              </tbody>
            </table>
          ) : (
            <p>통계 없음</p>
          )}
        </section>

        <section style={{ border: "1px solid #ccd", padding: 12, marginBottom: 14 }}>
          <h3 style={{ marginTop: 0 }}>문서 디버그</h3>
          <div style={{ display: "flex", gap: 8, marginBottom: 10 }}>
            <input
              value={documentId}
              onChange={(e) => setDocumentId(e.target.value)}
              placeholder="문서 식별자(document_id)"
              style={{ minWidth: 320 }}
            />
            <button onClick={() => void loadDocumentDebug()}>문서 조회</button>
          </div>
          {documentDebug ? (
            <>
              <p>
                title_mask: {documentDebug.title_mask.hash} (len={documentDebug.title_mask.length})
              </p>
              <p>
                assignment: {documentDebug.assignment.node_label ?? "-"} ({documentDebug.assignment.node_id ?? "-"}) / confidence:
                {" "}
                {formatMetric(documentDebug.assignment_confidence, 3)}
              </p>
              <p>
                trace_id: {documentDebug.trace_id ?? "-"} / request_id: {documentDebug.request_id ?? "-"}
              </p>
            </>
          ) : (
            <p>문서 디버그 데이터 없음</p>
          )}
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>문서</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>semantic</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>lexical</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>title_overlap</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>final</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>gate</th>
              </tr>
            </thead>
            <tbody>
              {documentDebug?.neighbors.map((neighbor) => (
                <tr key={neighbor.neighbor_doc_id}>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>
                    <div>{neighbor.title_mask.hash}</div>
                    <small>{neighbor.neighbor_doc_id}</small>
                  </td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{formatMetric(neighbor.channel_scores.semantic)}</td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{formatMetric(neighbor.channel_scores.lexical)}</td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{neighbor.edge_decision.title_overlap}</td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{formatMetric(neighbor.channel_scores.final)}</td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>
                    {neighbor.edge_decision.lexical_gate_passed ? "PASS" : "BLOCK"} / {neighbor.edge_decision.reason}
                  </td>
                </tr>
              )) ?? null}
              {(documentDebug?.neighbors.length ?? 0) === 0 ? (
                <tr>
                  <td colSpan={6} style={{ padding: "8px 4px" }}>
                    조회 결과가 없습니다.
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </section>

        <section style={{ border: "1px solid #ccd", padding: 12, marginBottom: 14 }}>
          <h3 style={{ marginTop: 0 }}>클러스터 디버그</h3>
          <div style={{ display: "flex", gap: 8, marginBottom: 10 }}>
            <input
              value={clusterId}
              onChange={(e) => setClusterId(e.target.value)}
              placeholder="클러스터 식별자(cluster_id)"
              style={{ minWidth: 320 }}
            />
            <button onClick={() => void loadClusterDebug()}>클러스터 조회</button>
          </div>
          {clusterDebug ? (
            <>
              <p>
                label: {clusterDebug.label} / members: {clusterDebug.member_count}
              </p>
              <p>
                trace_id: {clusterDebug.trace_id ?? "-"} / request_id: {clusterDebug.request_id ?? "-"}
              </p>
              <p>label_candidates: {clusterDebug.label_candidates.join(", ") || "-"}</p>
              <table style={{ width: "100%", borderCollapse: "collapse", marginBottom: 8 }}>
                <thead>
                  <tr>
                    <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>member_doc_id</th>
                    <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>title_mask</th>
                    <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>signals</th>
                  </tr>
                </thead>
                <tbody>
                  {clusterDebug.members.map((member) => (
                    <tr key={member.document_id}>
                      <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{member.document_id}</td>
                      <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{member.title_mask.hash}</td>
                      <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{member.signals.join(", ") || "-"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <h4 style={{ margin: "8px 0" }}>Exemplars</h4>
              <ul>
                {clusterDebug.exemplars.map((exemplar) => (
                  <li key={exemplar.document_id}>
                    {exemplar.document_id} / {exemplar.title_mask.hash} / avg={formatMetric(exemplar.avg_similarity, 3)}
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <p>클러스터 디버그 데이터 없음</p>
          )}
        </section>

        <section style={{ border: "1px solid #ccd", padding: 12 }}>
          <h3 style={{ marginTop: 0 }}>리빌드 디버그</h3>
          <div style={{ display: "flex", gap: 8, marginBottom: 10 }}>
            <input
              value={snapshotId}
              onChange={(e) => setSnapshotId(e.target.value)}
              placeholder="스냅샷 식별자(snapshot_id)"
              style={{ minWidth: 320 }}
            />
            <button onClick={() => void loadRebuildDebug()}>리빌드 조회</button>
          </div>
          {rebuildDebug ? (
            <>
              <p>
                snapshot: {rebuildDebug.snapshot_id} / status: {rebuildDebug.status}
              </p>
              <p>
                trace_id: {rebuildDebug.trace_id ?? "-"} / request_id: {rebuildDebug.request_id ?? "-"}
              </p>
              <h4 style={{ margin: "8px 0" }}>Parameters</h4>
              <pre>{JSON.stringify(rebuildDebug.parameters, null, 2)}</pre>
              <h4 style={{ margin: "8px 0" }}>Models</h4>
              <pre>{JSON.stringify(rebuildDebug.models, null, 2)}</pre>
              <h4 style={{ margin: "8px 0" }}>Decision Summary</h4>
              <pre>{JSON.stringify(rebuildDebug.decision_summary, null, 2)}</pre>
              <h4 style={{ margin: "8px 0" }}>Stage Logs</h4>
              <pre>{JSON.stringify(rebuildDebug.stage_logs, null, 2)}</pre>
            </>
          ) : (
            <p>리빌드 디버그 데이터 없음</p>
          )}
        </section>
      </Layout>
    );
  }

  function TreePolicyPage() {
    const [policy, setPolicy] = useState<TreePolicyResponse | null>(null);
    const [autoThreshold, setAutoThreshold] = useState("0.80");
    const [recommendThreshold, setRecommendThreshold] = useState("0.60");
    const [quarantineEnabled, setQuarantineEnabled] = useState(true);
    const [rerankerEnabled, setRerankerEnabled] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [message, setMessage] = useState<string | null>(null);

    const loadPolicy = async () => {
      if (!state.workspaceId) {
        setPolicy(null);
        return;
      }
      setError(null);
      try {
        const response = await api.request<TreePolicyResponse>("/admin/tree/policy", {}, true);
        setPolicy(response);
        setAutoThreshold(response.auto_threshold.toFixed(2));
        setRecommendThreshold(response.recommend_threshold.toFixed(2));
        setQuarantineEnabled(response.quarantine_enabled);
        setRerankerEnabled(response.reranker_enabled);
      } catch (e) {
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "정책 설정을 불러오지 못했습니다."));
      }
    };

    useEffect(() => {
      void loadPolicy();
    }, [state.workspaceId]);

    const savePolicy = async () => {
      if (!state.workspaceId) {
        setError("먼저 워크스페이스를 선택하세요.");
        return;
      }
      const auto = Number(autoThreshold);
      const recommend = Number(recommendThreshold);
      if (Number.isNaN(auto) || Number.isNaN(recommend)) {
        setError("임계값은 숫자로 입력하세요.");
        return;
      }
      if (recommend > auto) {
        setError("recommend_threshold는 auto_threshold보다 작거나 같아야 합니다.");
        return;
      }

      setError(null);
      setMessage(null);
      try {
        const response = await api.request<TreePolicyResponse>(
          "/admin/tree/policy",
          {
            method: "PATCH",
            body: JSON.stringify({
              auto_threshold: auto,
              recommend_threshold: recommend,
              quarantine_enabled: quarantineEnabled,
              reranker_enabled: rerankerEnabled
            })
          },
          true
        );
        setPolicy(response);
        setMessage("정책이 저장되었습니다. 다음 리빌드부터 반영됩니다.");
      } catch (e) {
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "정책 저장에 실패했습니다."));
      }
    };

    return (
      <Layout>
        <h2>정책 설정</h2>
        <p>워크스페이스별 자동 배치 임계값과 유보 정책을 조정합니다.</p>
        {error ? (
          <div style={{ border: "1px solid #d33", padding: 10, marginBottom: 12, background: "#fff3f3" }}>{error}</div>
        ) : null}
        {message ? (
          <div style={{ border: "1px solid #3a7", padding: 10, marginBottom: 12, background: "#effbf4" }}>{message}</div>
        ) : null}

        <section style={{ border: "1px solid #ccd", padding: 12, marginBottom: 14, maxWidth: 620 }}>
          <h3 style={{ marginTop: 0 }}>Threshold</h3>
          <div style={{ display: "grid", gap: 8 }}>
            <label style={{ display: "grid", gap: 4 }}>
              <span>auto_threshold (AUTO)</span>
              <input value={autoThreshold} onChange={(e) => setAutoThreshold(e.target.value)} />
            </label>
            <label style={{ display: "grid", gap: 4 }}>
              <span>recommend_threshold (RECOMMEND)</span>
              <input value={recommendThreshold} onChange={(e) => setRecommendThreshold(e.target.value)} />
            </label>
            <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input
                checked={quarantineEnabled}
                onChange={(e) => setQuarantineEnabled(e.target.checked)}
                type="checkbox"
              />
              quarantine_enabled
            </label>
            <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input
                checked={rerankerEnabled}
                onChange={(e) => setRerankerEnabled(e.target.checked)}
                type="checkbox"
              />
              reranker_enabled
            </label>
            <div style={{ display: "flex", gap: 8 }}>
              <button onClick={() => void savePolicy()}>저장</button>
              <button onClick={() => void loadPolicy()}>다시 불러오기</button>
            </div>
          </div>
        </section>

        <section style={{ border: "1px solid #ccd", padding: 12, maxWidth: 620 }}>
          <h3 style={{ marginTop: 0 }}>현재 값</h3>
          {policy ? (
            <pre>{JSON.stringify(policy, null, 2)}</pre>
          ) : (
            <p>정책 데이터 없음</p>
          )}
        </section>
      </Layout>
    );
  }

  function TreeRulesPage() {
    const [rules, setRules] = useState<UserRuleItem[]>([]);
    const [nodes, setNodes] = useState<Array<{ id: string; label: string }>>([]);
    const [ruleType, setRuleType] = useState("TITLE_CONTAINS");
    const [ruleValue, setRuleValue] = useState("");
    const [ruleEffect, setRuleEffect] = useState("HARD");
    const [nodeId, setNodeId] = useState("");
    const [sampleDocId, setSampleDocId] = useState("");
    const [preview, setPreview] = useState<UserRulePreviewResponse | null>(null);
    const [editingRuleId, setEditingRuleId] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [message, setMessage] = useState<string | null>(null);

    const load = async () => {
      if (!state.workspaceId) {
        setRules([]);
        setNodes([]);
        setPreview(null);
        return;
      }
      setError(null);
      try {
        const [ruleResponse, treeResponse] = await Promise.all([
          api.request<UserRuleListResponse>("/admin/tree/rules", {}, true),
          api.request<TreeActiveResponse>("/tree/active", {}, true)
        ]);
        setRules(ruleResponse.items);
        const selectableNodes = treeResponse.nodes
          .filter((node) => node.parent_id !== null && node.label !== "AutoDoc")
          .map((node) => ({ id: node.id, label: node.label }));
        setNodes(selectableNodes);
        setNodeId((prev) => prev || selectableNodes[0]?.id || "");
      } catch (e) {
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "규칙 목록을 불러오지 못했습니다."));
      }
    };

    useEffect(() => {
      void load();
    }, [state.workspaceId]);

    const resetForm = () => {
      setRuleType("TITLE_CONTAINS");
      setRuleValue("");
      setRuleEffect("HARD");
      setNodeId((prev) => prev || nodes[0]?.id || "");
      setEditingRuleId(null);
    };

    const saveRule = async () => {
      if (!state.workspaceId) {
        setError("먼저 워크스페이스를 선택하세요.");
        return;
      }
      if (!ruleValue.trim() || !nodeId) {
        setError("규칙 값과 대상 노드를 입력하세요.");
        return;
      }
      setError(null);
      setMessage(null);
      try {
        if (editingRuleId) {
          await api.request<UserRuleMutationResponse>(
            `/admin/tree/rules/${editingRuleId}`,
            {
              method: "PATCH",
              body: JSON.stringify({
                rule_type: ruleType,
                rule_value: ruleValue,
                rule_effect: ruleEffect,
                node_id: nodeId
              })
            },
            true
          );
          setMessage("규칙을 수정했습니다.");
        } else {
          await api.request<UserRuleMutationResponse>(
            "/admin/tree/rules",
            {
              method: "POST",
              body: JSON.stringify({
                rule_type: ruleType,
                rule_value: ruleValue,
                rule_effect: ruleEffect,
                node_id: nodeId
              })
            },
            true
          );
          setMessage("규칙을 생성했습니다.");
        }
        setPreview(null);
        await load();
        resetForm();
      } catch (e) {
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "규칙 저장에 실패했습니다."));
      }
    };

    const previewRule = async () => {
      if (!sampleDocId.trim()) {
        setError("테스트할 문서 식별자를 입력하세요.");
        return;
      }
      if (!ruleValue.trim() || !nodeId) {
        setError("규칙 값과 대상 노드를 입력하세요.");
        return;
      }
      setError(null);
      setMessage(null);
      try {
        const response = await api.request<UserRulePreviewResponse>(
          "/admin/tree/rules/preview",
          {
            method: "POST",
            body: JSON.stringify({
              document_id: sampleDocId,
              rule_type: ruleType,
              rule_value: ruleValue,
              rule_effect: ruleEffect,
              node_id: nodeId
            })
          },
          true
        );
        setPreview(response);
      } catch (e) {
        setPreview(null);
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "규칙 테스트에 실패했습니다."));
      }
    };

    const deleteRule = async (ruleId: string) => {
      setError(null);
      setMessage(null);
      try {
        await api.request(`/admin/tree/rules/${ruleId}`, { method: "DELETE" }, true);
        if (editingRuleId === ruleId) {
          resetForm();
        }
        setMessage("규칙을 삭제했습니다.");
        await load();
      } catch (e) {
        setError(localizeErrorMessage(e instanceof Error ? e.message : "", "규칙 삭제에 실패했습니다."));
      }
    };

    const startEdit = (rule: UserRuleItem) => {
      setEditingRuleId(rule.id);
      setRuleType(rule.rule_type);
      setRuleValue(rule.rule_value);
      setRuleEffect(rule.rule_effect);
      setNodeId(rule.node_id);
      setPreview(null);
      setMessage(null);
      setError(null);
    };

    return (
      <Layout>
        <h2>규칙 관리</h2>
        <p>조건 기반 라우팅 규칙을 생성/테스트/수정하고 즉시 적용할 수 있습니다.</p>
        {error ? (
          <div style={{ border: "1px solid #d33", padding: 10, marginBottom: 12, background: "#fff3f3" }}>{error}</div>
        ) : null}
        {message ? (
          <div style={{ border: "1px solid #3a7", padding: 10, marginBottom: 12, background: "#effbf4" }}>{message}</div>
        ) : null}

        <section style={{ border: "1px solid #ccd", padding: 12, marginBottom: 14, maxWidth: 760 }}>
          <h3 style={{ marginTop: 0 }}>{editingRuleId ? "규칙 수정" : "규칙 생성"}</h3>
          <div style={{ display: "grid", gap: 8 }}>
            <label style={{ display: "grid", gap: 4 }}>
              <span>rule_type</span>
              <select value={ruleType} onChange={(e) => setRuleType(e.target.value)}>
                <option value="TITLE_CONTAINS">TITLE_CONTAINS</option>
                <option value="ENTITY_CONTAINS">ENTITY_CONTAINS</option>
                <option value="SOURCE_TYPE">SOURCE_TYPE</option>
                <option value="AUTHOR">AUTHOR</option>
                <option value="FILENAME_EXT">FILENAME_EXT</option>
                <option value="TAG">TAG</option>
              </select>
            </label>
            <label style={{ display: "grid", gap: 4 }}>
              <span>rule_value</span>
              <input value={ruleValue} onChange={(e) => setRuleValue(e.target.value)} />
            </label>
            <label style={{ display: "grid", gap: 4 }}>
              <span>rule_effect</span>
              <select value={ruleEffect} onChange={(e) => setRuleEffect(e.target.value)}>
                <option value="HARD">HARD</option>
                <option value="SOFT">SOFT</option>
              </select>
            </label>
            <label style={{ display: "grid", gap: 4 }}>
              <span>target node</span>
              <select value={nodeId} onChange={(e) => setNodeId(e.target.value)}>
                {nodes.map((node) => (
                  <option key={node.id} value={node.id}>
                    {node.label} ({node.id})
                  </option>
                ))}
              </select>
            </label>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              <button onClick={() => void saveRule()}>{editingRuleId ? "수정 저장" : "규칙 생성"}</button>
              <button onClick={resetForm}>입력 초기화</button>
            </div>
          </div>
        </section>

        <section style={{ border: "1px solid #ccd", padding: 12, marginBottom: 14, maxWidth: 760 }}>
          <h3 style={{ marginTop: 0 }}>테스트 (Preview)</h3>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 10 }}>
            <input
              value={sampleDocId}
              onChange={(e) => setSampleDocId(e.target.value)}
              placeholder="문서 식별자(document_id)"
              style={{ minWidth: 320 }}
            />
            <button onClick={() => void previewRule()}>테스트</button>
          </div>
          {preview ? (
            <pre>{JSON.stringify(preview, null, 2)}</pre>
          ) : (
            <p>테스트 결과 없음</p>
          )}
        </section>

        <section style={{ border: "1px solid #ccd", padding: 12 }}>
          <h3 style={{ marginTop: 0 }}>규칙 목록</h3>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>rule_type</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>rule_value</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>effect</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>target</th>
                <th style={{ textAlign: "left", borderBottom: "1px solid #ddd" }}>actions</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => (
                <tr key={rule.id}>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{rule.rule_type}</td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{rule.rule_value}</td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>{rule.rule_effect}</td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>
                    {rule.node_label ?? "-"} ({rule.node_id})
                  </td>
                  <td style={{ borderBottom: "1px solid #eee", padding: "6px 4px" }}>
                    <div style={{ display: "flex", gap: 6 }}>
                      <button onClick={() => startEdit(rule)}>수정</button>
                      <button onClick={() => void deleteRule(rule.id)}>삭제</button>
                    </div>
                  </td>
                </tr>
              ))}
              {rules.length === 0 ? (
                <tr>
                  <td colSpan={5} style={{ padding: "8px 4px" }}>
                    등록된 규칙이 없습니다.
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
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
      <Route
        path="/tree-debug"
        element={
          <Protected>
            <TreeDebugPage />
          </Protected>
        }
      />
      <Route
        path="/tree-policy"
        element={
          <Protected>
            <TreePolicyPage />
          </Protected>
        }
      />
      <Route
        path="/tree-rules"
        element={
          <Protected>
            <TreeRulesPage />
          </Protected>
        }
      />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
