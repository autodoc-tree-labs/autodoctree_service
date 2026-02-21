# U-1210 — Workspace-first IA refactor for web-user app shell and workspace routing

## Context
- `web-user`는 상단 탭 중심 네비게이션(`워크스페이스/문서함/에디터/검색/질문함/트리`) 구조로 되어 있어 문서 중심 흐름이 끊긴다.
- 워크스페이스 전환/컨텍스트가 전역적으로 자연스럽게 연결되지 않아 탐색-편집-검색-정리의 연속 UX가 약하다.
- 사이드바 기반과 유사한 정보구조(Sidebar 중심 + 문서 중심 메인)가 필요하다.

## Goal
- `web-user`를 Workspace 중심 URL/레이아웃으로 재구성해 문서 탐색/편집/검색/트리/질문 흐름을 하나의 AppShell에서 자연스럽게 제공한다.

## Non-goals
- 백엔드 API 계약 자체 변경.
- 상용 디자인 에셋 복제.

## Scope
- Workspace-first AppShell (Sidebar + Thin Header + Main, 모바일 Drawer 대응).
- Workspace Switcher + last workspace 복원(localStorage).
- Sidebar Pages 트리(문서 parent-child 기반) + 필터.
- Sidebar Views 전환(Documents/Tree/Questions).
- Command Palette(Cmd/Ctrl+K): Open page/New page/View 이동.
- Workspace 중심 라우팅(`/w/:workspaceId/...`) 도입 + 레거시 경로 리다이렉트.
- 문서 편집 화면 breadcrumb/저장 상태/하위 페이지 생성 동선 유지.
- 문서함 카드/리스트 토글 + 정렬.
- 최소 E2E 3개 추가.
- README/DEV_SETUP에 IA/라우트/단축키 반영.

## API / Contracts
- 기존 API 유지:
  - `/workspaces`
  - `/documents`, `/documents/:id`
  - `/search`
  - `/trees`
  - `/questions`
- 프론트는 `X-Workspace-Id`를 workspace 컨텍스트 변경 시 일관되게 갱신.

## DB / Index changes
- 없음.

## Happy path
1. 로그인 후 앱은 마지막 사용 워크스페이스(또는 첫 워크스페이스)로 자동 진입한다.
2. Sidebar에서 문서/뷰를 선택해 `/w/:workspaceId/...` 경로로 이동한다.
3. Cmd/Ctrl+K로 문서 검색/새 페이지/뷰 이동을 수행한다.
4. 문서 편집 후 저장 시 상태가 갱신되고, 하위 페이지 생성 시 parent-child 관계가 유지된다.

## Edge cases
- 워크스페이스 목록이 비어 있으면 안내 상태를 노출한다.
- 검색/문서 로드 실패 시 오류 패널로 graceful fallback.
- 모바일에서 사이드바는 Drawer로 전환한다.

## Acceptance Criteria
- [ ] 상단 탭 네비게이션 대신 Sidebar + View 전환 구조가 적용된다.
- [ ] `/w/:workspaceId/...` 라우트로 문서/뷰 이동이 가능하다.
- [ ] Workspace 전환 시 문서/트리/질문/검색 데이터가 해당 workspace 기준으로 갱신된다.
- [ ] Cmd/Ctrl+K 커맨드 팔레트에서 문서 열기/새 페이지/뷰 이동이 동작한다.
- [ ] 문서 parent-child 트리가 사이드바와 편집 흐름에서 유지된다.
- [ ] E2E 테스트 3개 이상이 통과한다.

## Testing
- `pnpm -C web-user build`
- `pnpm -C web-user test:e2e`
- 수동 점검:
  - 로그인 후 자동 workspace 진입
  - Sidebar Pages 문서 열기
  - Command Palette 동작
  - Documents/Tree/Questions view 전환

## Observability
- 민감 텍스트(body/첨부 본문) 로그 금지.
- 프론트 오류는 사용자 친화 메시지로 표시하고 콘솔 폭주 방지.

## Rollout / Rollback
- Rollout: web-user 단독 배포.
- Rollback: AppShell/route 변경 커밋 revert로 기존 탭 기반 레이아웃 복원.

## Security / Privacy
- Workspace 컨텍스트(`X-Workspace-Id`) 누락 시 요청 차단 UX 유지.
- 토큰은 `sessionStorage` 범위 유지(장기 저장 금지).
