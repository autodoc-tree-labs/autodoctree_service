# U-0103 — Workspace selector + header indicator (X-Workspace-Id)


## Goal
워크스페이스 선택과 현재 workspace 표시.

## Scope
- GET /workspaces 호출
- 선택한 workspace를 앱 상태에 저장
- 모든 tenant API 요청에 `X-Workspace-Id` 추가
- 헤더에 현재 workspace 명 표시

## Acceptance Criteria
- workspace 전환 시 화면 상태 초기화(혼입 방지)

