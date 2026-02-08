# U-0113 — Error handling (401/403/5xx) + retry UX


## Goal
에러 표준 처리와 사용자 친화적 리트라이.

## Scope
- global error boundary
- 401: login redirect
- 403/404: 접근불가 메시지(테넌트 정보 노출 금지)
- 5xx: retry button

## Acceptance Criteria
- 에러가 앱 전체를 깨지 않음

