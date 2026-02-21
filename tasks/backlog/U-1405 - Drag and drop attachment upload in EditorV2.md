# U-1405 — Drag and drop attachment upload in EditorV2

## Context
- 현재 블록 에디터는 이미지/파일 업로드 버튼 또는 슬래시 메뉴로만 첨부가 가능하다.
- 사용자 요구사항은 데스크톱 파일을 에디터 영역으로 직접 드래그해 즉시 첨부하는 UX다.

## Goal
- `EditorV2`에서 파일을 드래그앤드롭하면 이미지는 이미지 블록, 그 외 파일은 파일 블록으로 자동 업로드/삽입된다.

## Non-goals
- 업로드 API/스토리지 정책 변경
- 파일 형식별 고급 렌더러 추가

## Scope
- 에디터 영역의 dragenter/dragover/drop 이벤트 처리
- 파일 MIME 기반 자동 블록 타입 결정(image vs file)
- 기존 `onUploadAttachment` 경로 재사용
- 드롭 포지션(가능한 경우)에 삽입, 실패 시 현재 커서 위치 삽입
- 업로드 진행률/에러 메시지 기존 상태 재사용

## API / Contracts
- 기존 API 재사용
  - `POST /attachments/presign`
  - `POST /attachments/complete`
  - `GET /documents/:id`
- 계약 변경 없음

## DB / Index changes
- 없음

## Happy path
1. 사용자가 로컬 파일을 에디터 본문 위로 드래그한다.
2. 드롭하면 파일이 업로드된다.
3. 업로드 완료 후 본문에 블록이 삽입된다.

## Edge cases
- 파일이 없는 드롭 이벤트는 무시한다.
- 업로드 실패 시 문서 편집 상태는 유지하고 에러 메시지만 표시한다.
- 에디터 비활성화 상태에서는 드롭 업로드를 비활성화한다.

## Acceptance Criteria
- [ ] 버튼 클릭 없이 드래그앤드롭만으로 첨부가 동작한다.
- [ ] 이미지 파일은 `image` 블록, 일반 파일은 `fileBlock`으로 삽입된다.
- [ ] `pnpm --filter web-user build` 통과
- [ ] 관련 E2E 테스트 통과

## Testing
- `pnpm --filter web-user build`
- `pnpm --filter web-user test:e2e --grep "drag and drop upload"`

## Observability
- 본문/파일 내용 로그 출력 금지
- 기존 업로드 실패 메시지 재사용

## Rollout / Rollback
- Rollout: web-user 배포 시 즉시 반영
- Rollback: U-1405 커밋 revert

## Security / Privacy
- 기존 첨부 업로드 권한/워크스페이스 스코프 검증 경로 재사용
- 민감 데이터 로그 금지
