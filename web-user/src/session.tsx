import { createContext, useContext, useEffect, useMemo, useState } from "react";

type SessionState = {
  accessToken: string | null;
  refreshToken: string | null;
  workspaceId: string | null;
  workspaceName: string | null;
};

type SessionApi = {
  state: SessionState;
  setTokens: (accessToken: string, refreshToken: string) => void;
  clearTokens: () => void;
  setWorkspace: (workspaceId: string, workspaceName: string) => void;
};

const SessionContext = createContext<SessionApi | null>(null);
const SESSION_STORAGE_KEY = "autodoc.user.session.v1";

const initialState: SessionState = {
  accessToken: null,
  refreshToken: null,
  workspaceId: null,
  workspaceName: null
};

const isNullableString = (value: unknown): value is string | null => value === null || typeof value === "string";

const readPersistedSession = (): SessionState => {
  if (typeof window === "undefined") {
    return initialState;
  }
  try {
    const raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) {
      return initialState;
    }
    const parsed = JSON.parse(raw) as Partial<SessionState>;
    if (
      !isNullableString(parsed.accessToken) ||
      !isNullableString(parsed.refreshToken) ||
      !isNullableString(parsed.workspaceId) ||
      !isNullableString(parsed.workspaceName)
    ) {
      return initialState;
    }
    return {
      accessToken: parsed.accessToken,
      refreshToken: parsed.refreshToken,
      workspaceId: parsed.workspaceId,
      workspaceName: parsed.workspaceName
    };
  } catch {
    return initialState;
  }
};

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<SessionState>(() => readPersistedSession());

  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }
    try {
      if (!state.accessToken && !state.refreshToken && !state.workspaceId && !state.workspaceName) {
        window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
        return;
      }
      window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(state));
    } catch {
      // Ignore storage failures in local/dev environments.
    }
  }, [state]);

  const value = useMemo<SessionApi>(
    () => ({
      state,
      setTokens: (accessToken: string, refreshToken: string) =>
        setState((prev) => ({ ...prev, accessToken, refreshToken })),
      clearTokens: () => setState(initialState),
      setWorkspace: (workspaceId: string, workspaceName: string) =>
        setState((prev) => ({ ...prev, workspaceId, workspaceName }))
    }),
    [state]
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession() {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error("Session context missing");
  }
  return context;
}
