import { ApiClient } from "@autodoctree/api-client";

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

export const createApiClient = (deps: {
  getToken: () => string | null;
  getWorkspaceId: () => string | null;
  onUnauthorized: () => Promise<boolean>;
}) =>
  new ApiClient(API_BASE, {
    getToken: deps.getToken,
    getWorkspaceId: deps.getWorkspaceId,
    onUnauthorized: deps.onUnauthorized
  });
