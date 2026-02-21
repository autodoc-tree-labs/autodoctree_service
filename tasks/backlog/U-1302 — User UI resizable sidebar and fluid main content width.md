# U-1302 — User UI: Resizable sidebar and fluid main content width

## Context
- 현재 워크스페이스 화면에서 좌측 패널 폭이 고정되어 있어 사용자가 문서 트리/본문 비율을 상황에 맞게 조절하기 어렵다.
- 대형 화면에서 우측 메인 영역이 상대적으로 비어 보이는 문제가 있어, 정보 밀도와 사용성에 손실이 발생한다.

## Goal
- 좌측 패널을 드래그로 가변 조절 가능하게 만들고, 우측 메인 콘텐츠가 화면 폭을 더 자연스럽게 활용하도록 레이아웃을 개선한다.

## Non-goals
- 문서 API/백엔드 계약 변경
- 문서 분류/트리 알고리즘 변경

## Scope
- `web-user` Layout에 리사이즈 핸들 추가(좌우 드래그)
- 사이드바 폭 상태를 workspace 단위 localStorage에 저장/복원
- 키보드(좌우/Home/End)로도 폭 조절 가능하게 접근성 보완
- 우측 메인 영역 max-width 제한 완화로 빈 여백 축소
- 모바일(좁은 화면)에서는 기존 오버레이 사이드바 동작 유지

## API / Contracts
- 없음

## DB / Index changes
- 없음

## Happy path
- 사용자가 사이드바 경계 핸들을 드래그한다.
- 사이드바 폭이 즉시 줄어들거나 늘어난다.
- 메인 영역이 남은 폭을 자연스럽게 차지한다.
- 새로고침 후에도 workspace별 폭이 복원된다.

## Edge cases
- 최소/최대 폭 제한 밖으로 드래그해도 안전하게 clamp
- 모바일 브레이크포인트에서는 핸들 숨김 및 기존 드로어 UX 유지
- 리사이즈 중 텍스트 선택/예기치 않은 드래그 부작용 최소화

## Acceptance Criteria
- [ ] 사이드바를 마우스로 좌우 드래그해 폭 조절 가능
- [ ] 우측 메인 영역의 과도한 빈 영역이 줄어듦
- [ ] workspace별 폭이 localStorage에 저장/복원됨
- [ ] `pnpm -C web-user build` 통과

## Testing
- Frontend build: `pnpm -C web-user build`

## Observability
- 클라이언트 레이아웃 변경이며 서버 로그/메트릭 변경 없음
- 민감 데이터 로그 추가 없음

## Rollout / Rollback
- 롤아웃: web-user 배포 즉시 반영
- 롤백: 관련 커밋 revert

## Security / Privacy
- tenant header, auth/session, API scope 로직 변경 없음
