# U-1209 — Persist login session across refresh in web-user

## Context
- `web-user` keeps auth tokens only in in-memory React state.
- Browser refresh clears memory, so users are redirected to `/login` and must sign in again.

## Goal
- Refreshing the browser should keep users logged in within the same browser tab session.

## Non-goals
- Introducing long-term token persistence in `localStorage`.
- Changing backend auth contract to cookie-based auth.

## Scope
- Persist `accessToken`, `refreshToken`, selected workspace info to `sessionStorage`.
- Rehydrate session on app boot.
- Keep logout behavior clearing persisted session.
- Keep existing refresh-token flow unchanged.

## API / Contracts
- No API changes.

## DB / Index changes
- None.

## Happy path
1. User logs in.
2. Session is saved in `sessionStorage`.
3. User refreshes browser.
4. App rehydrates session and keeps protected routes accessible.

## Edge cases
- Corrupted `sessionStorage` payload: ignore and fall back to logged-out state.
- Storage write/read errors: fail safely without app crash.

## Acceptance Criteria
- [ ] Login 후 새로고침해도 로그인 상태가 유지된다.
- [ ] 로그아웃 시 세션이 메모리+스토리지에서 제거된다.
- [ ] 저장된 세션 포맷이 손상되어도 앱이 깨지지 않는다.

## Testing
- Build/typecheck for `web-user`.
- Manual verification: login → refresh → protected page remains.

## Observability
- No sensitive data logging added.

## Rollout / Rollback
- Rollout: deploy frontend only.
- Rollback: revert session persistence code in `web-user/src/session.tsx`.

## Security / Privacy
- Avoid `localStorage`; use per-tab `sessionStorage` to reduce token persistence scope.
