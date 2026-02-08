# I-0103 — CI quality gates (services + web)


## Goal
PR merge 전에 빌드/테스트/포맷이 통과해야 한다.

## Scope
- GitHub Actions:
  - services: gradle build + test + ktlint/spotless
  - web: pnpm install + lint + build + tests (if added)
- 캐시: Gradle, pnpm store

## Acceptance Criteria
- failing test/lint blocks merge

## Testing
- CI workflow 자체 테스트는 최소로 문서화

