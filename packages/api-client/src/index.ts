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

export class ApiError extends Error {
  readonly status: number;
  readonly payload: unknown;

  constructor(status: number, message: string, payload: unknown) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

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
      const payload = await parseErrorPayload(response);
      throw new ApiError(response.status, toSafeMessage(response.status, payload), payload);
    }

    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }
}

const parseErrorPayload = async (response: Response): Promise<unknown> => {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }
  const text = await response.text();
  return text || null;
};

const toSafeMessage = (status: number, payload: unknown): string => {
  if (status === 401) {
    return "Session expired. Please sign in again.";
  }
  if (status === 403 || status === 404) {
    return "Access denied.";
  }
  if (status >= 500) {
    return "Server error. Please retry.";
  }
  if (typeof payload === "string" && payload.trim().length > 0) {
    return payload;
  }
  if (payload && typeof payload === "object" && "error" in payload) {
    const errorPayload = (payload as { error?: { message?: string } }).error;
    if (errorPayload?.message) {
      return errorPayload.message;
    }
  }
  return `Request failed with ${status}`;
};
