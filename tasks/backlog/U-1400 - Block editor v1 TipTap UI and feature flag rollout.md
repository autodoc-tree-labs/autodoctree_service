# U-1400 — Block editor v1 TipTap UI and feature flag rollout

## Context
- 현재 문서 편집 UI는 markdown textarea 단일 모드로, 블록 중심 작성 경험이 부족합니다.
- 기존 UX를 깨지 않기 위해 feature flag 기반 점진 전환이 필요합니다.

## Goal
- TipTap 기반 EditorV2를 feature flag로 도입하고, 블록 편집/저장/불러오기/업로드 핵심 UX를 제공한다.

## Non-goals
- multi-user 실시간 동시편집
- 완전한 parity

## Scope
- `VITE_FEATURE_BLOCK_EDITOR` 플래그 추가
- EditorV1(textarea) 유지 + EditorV2 공존
- slash menu(`/`) + 주요 블록 삽입
- heading/list/todo/toggle/callout/quote/divider/code/table/toc/image/file
- 블록 재정렬(드래그) + 모바일 up/down fallback
- Cmd/Ctrl+S 저장 유지
- 저장 상태/마지막 수정자/수정 시각 표시

## API / Contracts
- 문서 요청/응답에 `blocks_json` 사용
- 첨부 업로드 API 재사용(`presign`/`complete`)

## DB / Index changes
- 없음(백엔드 티켓 B-1400 의존)

## Happy path
- 플래그 ON 시 EditorV2 로드
- 문서 수정 후 저장하면 blocks_json + markdown 동기화
- 문서 재열람 시 블록 상태 유지

## Edge cases
- 플래그 OFF 시 기존 textarea 유지
- 기존 markdown-only 문서 자동 변환
- 업로드 실패 시 에디터 본문 무결성 유지

## Acceptance Criteria
- [ ] slash menu keyboard UX
- [ ] 블록 삽입/저장/불러오기
- [ ] 업로드 블록 표시
- [ ] 저장 상태/메타 정보 표기

## Testing
- Front build/lint
- editor route smoke (manual + e2e scope)

## Observability
- 저장/업로드 실패 사용자 안내
- 민감 본문 로그 미노출

## Rollout / Rollback
- env flag로 단계 배포
- 즉시 V1 회귀 가능

## Security / Privacy
- 워크스페이스 헤더/세션 컨텍스트 사용
- 클라이언트 측 민감 데이터 localStorage 저장 금지
