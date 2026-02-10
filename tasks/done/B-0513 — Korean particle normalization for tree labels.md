# B-0513 — Korean particle normalization for tree labels

## Context
- 현재 트리 라벨 생성/유사도 계산에서 한국어 조사(와/과/은/는/이/가/을/를 등)가 정규화되지 않아 `섹스`와 `섹스와`처럼 의미가 같은 토큰이 분리된다.

## Goal
- 한국어 조사 정규화를 통해 동일 의미 토큰을 하나로 통합하고, 불필요한 노드 분리를 줄인다.

## Scope
- TreeLabeler 토크나이저에 Hangul 조사 제거 정규화 추가.
- Neighbor lexical similarity가 정규화 토큰을 사용하도록 연동(기존 tokenize 경로 유지).
- 관련 단위 테스트 추가.

## Non-goals
- 형태소 분석기 도입 없음.
- API/DB 스키마 변경 없음.

## Acceptance Criteria
- [ ] `섹스`와 `섹스와`가 동일 핵심 토큰으로 처리된다.
- [ ] 라벨 생성 시 조사형 노이즈가 감소한다.
- [ ] `TreeAlgorithmsTest` 통과.

## Testing
- `./gradlew -p services :doc-api:test --tests "com.autodoctree.api.domain.TreeAlgorithmsTest"`

## Rollout / Rollback
- 백엔드 재시작 + 트리 재빌드로 반영.
- 문제 시 해당 커밋 롤백.
