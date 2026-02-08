import { createContext, useContext, useMemo, useState } from "react";

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

const initialState: SessionState = {
  accessToken: null,
  refreshToken: null,
  workspaceId: null,
  workspaceName: null
};

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<SessionState>(initialState);

  const value = useMemo<SessionApi>(
    () => ({
      state,
      setTokens: (accessToken, refreshToken) => setState((prev) => ({ ...prev, accessToken, refreshToken })),
      clearTokens: () => setState(initialState),
      setWorkspace: (workspaceId, workspaceName) => setState((prev) => ({ ...prev, workspaceId, workspaceName }))
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
