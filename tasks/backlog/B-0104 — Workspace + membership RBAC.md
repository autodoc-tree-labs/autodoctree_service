# B-0104 — Workspace + membership RBAC


## Goal
워크스페이스 생성/조회 및 멤버 관리 + 역할 기반 접근 제어.

## Scope
- GET/POST /workspaces
- GET/POST /workspaces/{id}/members
- roles: OWNER/MEMBER/VIEWER
- RBAC 중앙화(어노테이션/미들웨어)

## Acceptance Criteria
- Viewer는 write/feedback/admin 불가
- Owner는 멤버 관리 가능
- cross-tenant 접근 403/404

