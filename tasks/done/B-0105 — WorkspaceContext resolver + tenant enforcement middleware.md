# B-0105 — WorkspaceContext resolver + tenant enforcement middleware


## Goal
요청마다 WorkspaceContext를 강제하고 tenant scope 누락을 구조적으로 방지.

## Scope
- header `X-Workspace-Id` 기반 workspace 선택
- membership 검증 후 WorkspaceContext(userId, workspaceId, role) 생성
- tenant-scoped endpoint는 WorkspaceContext 없으면 400/403로 fail closed
- security metric: tenant_scope_missing_total

## Acceptance Criteria
- tenant 엔드포인트에서 workspace header 누락 시 실패
- 유효하지 않은 workspace 접근은 fail closed

