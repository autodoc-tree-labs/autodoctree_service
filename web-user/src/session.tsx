import { createContext, useContext, useEffect, useMemo, useState } from "react";

export type SessionAccount = {
  id: string;
  email: string;
  accessToken: string;
  refreshToken: string;
  workspaceId: string | null;
  workspaceName: string | null;
  lastUsedAt: string;
};

type SessionState = {
  activeAccountId: string | null;
  email: string | null;
  accounts: SessionAccount[];
  accessToken: string | null;
  refreshToken: string | null;
  workspaceId: string | null;
  workspaceName: string | null;
};

type UpsertAccountInput = {
  email?: string | null;
  accessToken: string;
  refreshToken: string;
  workspaceId?: string | null;
  workspaceName?: string | null;
};

type SessionApi = {
  state: SessionState;
  setTokens: (accessToken: string, refreshToken: string) => void;
  upsertAccount: (input: UpsertAccountInput, options?: { activate?: boolean; accountId?: string | null }) => string;
  switchAccount: (accountId: string) => void;
  removeAccount: (accountId: string) => void;
  clearTokens: () => void;
  clearAllAccounts: () => void;
  setWorkspace: (workspaceId: string, workspaceName: string) => void;
};

const SessionContext = createContext<SessionApi | null>(null);
const LEGACY_SESSION_STORAGE_KEY = "autodoc.user.session.v1";
const SESSION_ACCOUNTS_STORAGE_KEY = "autodoc.user.sessions.v2";

type PersistedSessionV2 = {
  activeAccountId: string | null;
  accounts: SessionAccount[];
};

const initialPersistedState: PersistedSessionV2 = {
  activeAccountId: null,
  accounts: []
};

const isNullableString = (value: unknown): value is string | null => value === null || typeof value === "string";
const isString = (value: unknown): value is string => typeof value === "string";

const parseTokenEmail = (token: string | null): string | null => {
  if (!token) {
    return null;
  }
  const segments = token.split(".");
  if (segments.length < 2) {
    return null;
  }
  try {
    const base64 = segments[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const decoded = atob(padded);
    const claims = JSON.parse(decoded) as Record<string, unknown>;
    const email = isString(claims.email) ? claims.email : isString(claims.sub) ? claims.sub : null;
    return email ? email.trim().toLowerCase() : null;
  } catch {
    return null;
  }
};

const createAccountId = (): string => {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `acc-${Math.random().toString(36).slice(2, 10)}`;
};

const normalizeAccount = (account: SessionAccount): SessionAccount => {
  const email = account.email.trim().toLowerCase();
  return {
    id: account.id,
    email,
    accessToken: account.accessToken,
    refreshToken: account.refreshToken,
    workspaceId: account.workspaceId,
    workspaceName: account.workspaceName,
    lastUsedAt: account.lastUsedAt
  };
};

const isValidAccount = (value: unknown): value is SessionAccount => {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<SessionAccount>;
  return (
    isString(candidate.id) &&
    isString(candidate.email) &&
    isString(candidate.accessToken) &&
    isString(candidate.refreshToken) &&
    isNullableString(candidate.workspaceId) &&
    isNullableString(candidate.workspaceName) &&
    isString(candidate.lastUsedAt)
  );
};

const isValidPersistedV2 = (value: unknown): value is PersistedSessionV2 => {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<PersistedSessionV2>;
  if (!isNullableString(candidate.activeAccountId) || !Array.isArray(candidate.accounts)) {
    return false;
  }
  return candidate.accounts.every((account) => isValidAccount(account));
};

const deriveState = (persisted: PersistedSessionV2): SessionState => {
  const accounts = persisted.accounts.map(normalizeAccount);
  const activeAccount =
    accounts.find((account) => account.id === persisted.activeAccountId) ??
    accounts[0] ??
    null;
  return {
    activeAccountId: activeAccount?.id ?? null,
    email: activeAccount?.email ?? null,
    accounts,
    accessToken: activeAccount?.accessToken ?? null,
    refreshToken: activeAccount?.refreshToken ?? null,
    workspaceId: activeAccount?.workspaceId ?? null,
    workspaceName: activeAccount?.workspaceName ?? null
  };
};

const readPersistedSession = (): PersistedSessionV2 => {
  if (typeof window === "undefined") {
    return initialPersistedState;
  }
  try {
    const rawV2 = window.localStorage.getItem(SESSION_ACCOUNTS_STORAGE_KEY);
    if (rawV2) {
      const parsedV2 = JSON.parse(rawV2) as unknown;
      if (isValidPersistedV2(parsedV2)) {
        return {
          activeAccountId: parsedV2.activeAccountId,
          accounts: parsedV2.accounts.map(normalizeAccount)
        };
      }
    }
  } catch {
    // Ignore v2 parsing failures and fallback to legacy key.
  }

  try {
    const rawLegacy = window.sessionStorage.getItem(LEGACY_SESSION_STORAGE_KEY);
    if (!rawLegacy) {
      return initialPersistedState;
    }
    const parsed = JSON.parse(rawLegacy) as Partial<SessionState>;
    if (
      !isNullableString(parsed.accessToken) ||
      !isNullableString(parsed.refreshToken) ||
      !isNullableString(parsed.workspaceId) ||
      !isNullableString(parsed.workspaceName)
    ) {
      return initialPersistedState;
    }
    if (!parsed.accessToken || !parsed.refreshToken) {
      return initialPersistedState;
    }
    const email = parseTokenEmail(parsed.accessToken) ?? "unknown@local";
    const account: SessionAccount = {
      id: createAccountId(),
      email,
      accessToken: parsed.accessToken,
      refreshToken: parsed.refreshToken,
      workspaceId: parsed.workspaceId,
      workspaceName: parsed.workspaceName,
      lastUsedAt: new Date().toISOString()
    };
    return {
      activeAccountId: account.id,
      accounts: [account]
    };
  } catch {
    return initialPersistedState;
  }
};

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [persisted, setPersisted] = useState<PersistedSessionV2>(() => readPersistedSession());
  const state = useMemo<SessionState>(() => deriveState(persisted), [persisted]);

  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }
    try {
      const normalized = {
        activeAccountId: state.activeAccountId,
        accounts: state.accounts
      };
      if (normalized.accounts.length === 0) {
        window.localStorage.removeItem(SESSION_ACCOUNTS_STORAGE_KEY);
        window.sessionStorage.removeItem(LEGACY_SESSION_STORAGE_KEY);
        return;
      }
      window.localStorage.setItem(SESSION_ACCOUNTS_STORAGE_KEY, JSON.stringify(normalized));

      const active = normalized.accounts.find((account) => account.id === normalized.activeAccountId) ?? normalized.accounts[0];
      window.sessionStorage.setItem(
        LEGACY_SESSION_STORAGE_KEY,
        JSON.stringify({
          accessToken: active.accessToken,
          refreshToken: active.refreshToken,
          workspaceId: active.workspaceId,
          workspaceName: active.workspaceName
        })
      );
    } catch {
      // Ignore storage failures in local/dev environments.
    }
  }, [state]);

  const value = useMemo<SessionApi>(
    () => ({
      state,
      setTokens: (accessToken: string, refreshToken: string) =>
        setPersisted((prev) => {
          const activeId = prev.activeAccountId;
          const now = new Date().toISOString();
          const tokenEmail = parseTokenEmail(accessToken);
          if (activeId) {
            const updated = prev.accounts.map((account) =>
              account.id === activeId
                ? {
                    ...account,
                    email: tokenEmail ?? account.email,
                    accessToken,
                    refreshToken,
                    lastUsedAt: now
                  }
                : account
            );
            return {
              activeAccountId: activeId,
              accounts: updated
            };
          }
          const account: SessionAccount = {
            id: createAccountId(),
            email: tokenEmail ?? "unknown@local",
            accessToken,
            refreshToken,
            workspaceId: null,
            workspaceName: null,
            lastUsedAt: now
          };
          return {
            activeAccountId: account.id,
            accounts: [account, ...prev.accounts]
          };
        }),
      upsertAccount: (input, options) => {
        let accountId = options?.accountId ?? null;
        setPersisted((prev) => {
          const email = (input.email ?? parseTokenEmail(input.accessToken) ?? "unknown@local").trim().toLowerCase();
          const now = new Date().toISOString();
          const explicitAccount = accountId ? prev.accounts.find((account) => account.id === accountId) : null;
          const matchedByEmail = prev.accounts.find((account) => account.email.toLowerCase() === email);
          const target = explicitAccount ?? matchedByEmail ?? null;

          if (target) {
            accountId = target.id;
            const updated = prev.accounts.map((account) =>
              account.id === target.id
                ? {
                    ...account,
                    email,
                    accessToken: input.accessToken,
                    refreshToken: input.refreshToken,
                    workspaceId: input.workspaceId ?? account.workspaceId ?? null,
                    workspaceName: input.workspaceName ?? account.workspaceName ?? null,
                    lastUsedAt: now
                  }
                : account
            );
            return {
              activeAccountId: options?.activate === false ? prev.activeAccountId : target.id,
              accounts: updated
            };
          }

          const nextId = createAccountId();
          accountId = nextId;
          const nextAccount: SessionAccount = {
            id: nextId,
            email,
            accessToken: input.accessToken,
            refreshToken: input.refreshToken,
            workspaceId: input.workspaceId ?? null,
            workspaceName: input.workspaceName ?? null,
            lastUsedAt: now
          };
          return {
            activeAccountId: options?.activate === false ? prev.activeAccountId ?? nextId : nextId,
            accounts: [nextAccount, ...prev.accounts]
          };
        });
        return accountId ?? "";
      },
      switchAccount: (accountId: string) => {
        setPersisted((prev) => {
          if (!prev.accounts.some((account) => account.id === accountId)) {
            return prev;
          }
          return {
            activeAccountId: accountId,
            accounts: prev.accounts.map((account) =>
              account.id === accountId
                ? {
                    ...account,
                    lastUsedAt: new Date().toISOString()
                  }
                : account
            )
          };
        });
      },
      removeAccount: (accountId: string) => {
        setPersisted((prev) => {
          const remaining = prev.accounts.filter((account) => account.id !== accountId);
          const nextActive =
            remaining.length === 0
              ? null
              : prev.activeAccountId === accountId
                ? remaining[0].id
                : prev.activeAccountId;
          return {
            activeAccountId: nextActive,
            accounts: remaining
          };
        });
      },
      clearTokens: () => setPersisted(initialPersistedState),
      clearAllAccounts: () => setPersisted(initialPersistedState),
      setWorkspace: (workspaceId: string, workspaceName: string) =>
        setPersisted((prev) => {
          if (!prev.activeAccountId) {
            return prev;
          }
          return {
            activeAccountId: prev.activeAccountId,
            accounts: prev.accounts.map((account) =>
              account.id === prev.activeAccountId
                ? {
                    ...account,
                    workspaceId,
                    workspaceName,
                    lastUsedAt: new Date().toISOString()
                  }
                : account
            )
          };
        })
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
