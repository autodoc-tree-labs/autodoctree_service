# U-1401 — Block editor slash command palette expansion

## Context
- 현재 편집 경험은 "제목 + 본문" 중심이며, 블록 기반 작성 플로우가 제한적입니다.
- 사용자는 `/` 입력으로 다양한 블록을 빠르게 삽입하는 에디터 UX를 기대합니다.
- 기존 검색/임베딩 파이프라인은 `body_markdown`/`body_text`를 계속 사용해야 하므로, 에디터 UI 확장 시 저장 호환성을 유지해야 합니다.

## Goal
- EditorV2에서 `/` 슬래시 커맨드 메뉴로 주요 블록을 키보드 중심으로 삽입/변환할 수 있게 한다.

## Non-goals
- 실시간 협업/동시편집
- Notion DB 레벨 기능(관계형 속성, rollup, formula)
- 링크 미리보기 외부 크롤러 신규 구축

## Scope
- `/` 입력 시 커맨드 팝오버 표시(추천/기본 블록/미디어/데이터 섹션)
- 필수 커맨드: Text, H1/H2/H3, Bullet, Numbered, Todo, Toggle, Quote, Callout, Divider, Code, Table v1, TOC, Image, File
- 별칭/축약 명령 지원: `/h1`, `/h2`, `/todo`, `/code`, `/table`, `/toc`
- 한글/영문 키워드 검색 및 fuzzy filtering
- 키보드 UX: `↑/↓`, `Enter`, `Esc`, 마우스 클릭 선택
- 선택 영역이 있을 때 "블록 변환", 없을 때 "새 블록 삽입" 규칙 정립
- 메뉴 위치/레이어 안정화(뷰포트 경계에서 잘리지 않음, 오버레이 겹침 방지)
- 이미지/파일 명령은 기존 attachment 업로드 플로우를 재사용해 블록 생성
- Cmd/Ctrl+S 저장 및 `blocks_json -> markdown/body_text` 동기화 동작 유지
- feature flag OFF 시 기존 EditorV1 동작 유지

## API / Contracts
- 신규 API 없음(기존 문서/첨부 API 재사용)
- 문서 저장 payload는 `blocks_json` + `body_markdown` 유지
- API 변경이 없더라도 동작 변경 사항은 `API_SURFACE.md`에 명시

## DB / Index changes
- 없음
- 검색/임베딩용 `body_markdown`, `body_text` 산출 결과 품질 회귀가 없어야 함

## Happy path
1. 사용자가 EditorV2에서 `/` 입력
2. 슬래시 메뉴가 커서 근처에 열리고 기본 추천 항목 노출
3. 검색어 입력 또는 방향키/엔터로 명령 선택
4. 선택한 타입의 블록이 현재 위치에 삽입/변환
5. 저장 후 재열람 시 블록 타입과 순서가 유지

## Edge cases
- 한글 IME 조합 중(`/`) 오작동 금지
- 코드 블록 내부/URL 문자열 입력 중에는 메뉴 오픈 조건 제어
- 문서 최상단/최하단/스크롤 중에서도 메뉴 위치 정확성 보장
- 업로드 실패 시 문서 본문 손상 없이 에러 표시 및 재시도 가능
- 메뉴 다중 오픈/중첩 방지(항상 하나의 메뉴만 표시)

## Acceptance Criteria
- [ ] `/` 입력 시 슬래시 메뉴가 의도한 위치에 안정적으로 노출된다.
- [ ] 필수 커맨드(Text, H1/H2/H3, List, Todo, Toggle, Quote, Callout, Divider, Code, Table, TOC, Image, File)가 모두 동작한다.
- [ ] 키보드 전용 조작(`↑/↓`, `Enter`, `Esc`)으로 메뉴 탐색/선택/닫기가 가능하다.
- [ ] 블록 삽입/변환 후 저장-새로고침-재열람 시 내용이 유지된다.
- [ ] `body_markdown`, `body_text`가 최신 블록 상태와 동기화된다.
- [ ] feature flag OFF에서는 기존 textarea 편집 경험이 유지된다.
- [ ] 메뉴 오버레이가 다른 UI와 겹쳐 깨지지 않는다.
- [ ] README/DEV_SETUP 사용법이 최신 상태로 업데이트된다.

## Testing
- Unit tests
- slash query parser/fuzzy filter 테스트
- command -> block insertion mapping 테스트
- Integration tests
- editor save/load round-trip(`blocks_json`, `body_markdown`) 테스트
- attachment command(image/file) 삽입 테스트
- E2E tests
- `/` 메뉴 열기/검색/선택/삽입/저장 시나리오
- mobile fallback(터치 환경에서 기본 삽입 동작) 스모크

## Observability
- 로그에는 블록 본문/첨부 본문을 남기지 않는다.
- 에디터 이벤트는 타입/성공여부/latency 중심으로 기록(콘텐츠 제외)
- 저장 실패/업로드 실패에 대해 사용자 토스트 및 request_id 노출

## Rollout / Rollback
- `VITE_FEATURE_BLOCK_EDITOR` 기반 점진 배포
- 필요 시 즉시 EditorV1로 회귀 가능
- 운영 반영 전, dev/staging에서 문서 생성/수정/검색 회귀 테스트 수행

## Security / Privacy
- 워크스페이스 컨텍스트(`X-Workspace-Id`)를 모든 요청에 유지
- 첨부 업로드는 기존 권한/소유권 검증 API를 재사용
- 콘텐츠/첨부 원문은 로그/메트릭에 포함하지 않는다.
