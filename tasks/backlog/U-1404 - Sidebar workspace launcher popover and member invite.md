# U-1404 — Sidebar workspace launcher popover and member invite

## Context
- 현재 사이드바는 `WORKSPACE` 드롭다운, 하단 `워크스페이스 설정/로그아웃`, 모바일 헤더 메뉴가 분산되어 있어 워크스페이스 전환과 계정 액션 동선이 끊긴다.
- 사용자 요구사항은 좌측 상단 로고 클릭 한 곳에서 워크스페이스 목록/설정/멤버 초대를 처리하는 흐름이다.

## Goal
- 좌측 상단 브랜드 클릭 시 워크스페이스 런처 팝오버를 열고, 전환/설정/멤버 초대/로그아웃을 단일 진입점으로 통합한다.

## Non-goals
- 백엔드 권한 모델 변경
- 워크스페이스 관리 페이지 전체 재설계
- 초대 수락 플로우 UI 추가

## Scope
- 사이드바 상단 `WORKSPACE` 드롭다운 제거
- 좌측 상단 브랜드 클릭 팝오버 추가
  - 현재 워크스페이스 요약
  - 워크스페이스 목록 + 전환
  - 워크스페이스 설정 이동
  - 멤버 초대(이메일/역할 입력 후 invite token 생성)
  - 로그아웃
- 사이드바 하단 및 모바일 헤더 메뉴의 중복 `워크스페이스 설정/로그아웃` 액션 제거
- 접근성: `aria-expanded`, `aria-controls`, `Esc` 닫기, 외부 클릭 닫기

## API / Contracts
- 기존 API 재사용
  - `GET /workspaces`
  - `POST /workspaces/{workspaceId}/invites`
- API 변경 없음

## DB / Index changes
- 없음

## Happy path
1. 사용자가 좌측 상단 로고를 클릭한다.
2. 팝오버에서 다른 워크스페이스를 클릭하면 즉시 해당 workspace route로 전환된다.
3. `설정` 버튼으로 `/workspace` 페이지로 이동한다.
4. `멤버 초대`에서 이메일/역할 입력 후 초대 토큰이 생성되고 성공 메시지가 보인다.
5. `로그아웃` 버튼으로 세션이 정리되고 로그인 화면으로 이동한다.

## Edge cases
- 워크스페이스 목록 로드 실패 시 팝오버 내에서 오류 메시지/재시도 동선 제공
- OWNER가 아닌 사용자가 초대 시도 시 403 에러를 사용자 메시지로 노출
- 활성 워크스페이스가 없으면 전환/초대 액션 비활성화

## Acceptance Criteria
- [ ] 사이드바 `WORKSPACE` 드롭다운이 제거된다.
- [ ] 좌측 상단 브랜드 클릭으로 워크스페이스 목록 팝오버가 열린다.
- [ ] 설정/멤버 초대/로그아웃이 팝오버에서 동작한다.
- [ ] 기존 하단 설정/로그아웃 버튼이 제거된다.
- [ ] `pnpm --filter web-user build` 통과
- [ ] 관련 E2E 테스트 통과

## Testing
- `pnpm --filter web-user build`
- `pnpm --filter web-user test:e2e --grep "workspace launcher"`
- `pnpm --filter web-user test:e2e --grep "switching workspace updates route and page tree"`

## Observability
- 프론트 UI 변경으로 신규 서버 로그 스키마 추가 없음
- 초대 토큰 값은 콘솔/로그 출력 금지

## Rollout / Rollback
- Rollout: web-user 배포 시 즉시 반영
- Rollback: U-1404 커밋 revert

## Security / Privacy
- 워크스페이스 전환 후 `X-Workspace-Id` 컨텍스트 유지
- 초대 API는 기존 owner 권한 검증에 의존
- 민감 정보(토큰/본문) 로그 금지
