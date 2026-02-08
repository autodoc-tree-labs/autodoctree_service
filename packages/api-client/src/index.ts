export type Tokens = {
  accessToken: string;
  refreshToken: string;
};

export type Workspace = {
  id: string;
  name: string;
  role: "OWNER" | "MEMBER" | "VIEWER";
};

export type RequestContext = {
  getToken: () => string | null;
  getWorkspaceId: () => string | null;
  onUnauthorized?: () => Promise<boolean>;
};

const generateRequestId = () => {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `req_${Date.now()}_${Math.random().toString(16).slice(2)}`;
};

export class ApiClient {
  constructor(private readonly baseUrl: string, private readonly context: RequestContext) {}

  async request<T>(path: string, init: RequestInit = {}, tenantScoped = false): Promise<T> {
    const headers = new Headers(init.headers ?? {});
    headers.set("Content-Type", "application/json");
    headers.set("X-Request-Id", generateRequestId());

    const token = this.context.getToken();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    if (tenantScoped) {
      const workspaceId = this.context.getWorkspaceId();
      if (workspaceId) {
        headers.set("X-Workspace-Id", workspaceId);
      }
    }

    const execute = async () =>
      fetch(`${this.baseUrl}${path}`, {
        ...init,
        headers
      });

    let response = await execute();
    if (response.status === 401 && this.context.onUnauthorized) {
      const recovered = await this.context.onUnauthorized();
      if (recovered) {
        const refreshedToken = this.context.getToken();
        if (refreshedToken) {
          headers.set("Authorization", `Bearer ${refreshedToken}`);
        }
        response = await execute();
      }
    }

    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `Request failed with ${response.status}`);
    }

    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }
}
