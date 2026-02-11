# I-0106 — Config/secrets/feature flags baseline


## Goal
환경 분리 + 플래그 기반 안전 배포를 가능하게 한다.

## Scope
- services application-local.yml 템플릿
- feature flags:
  - feature.auto_tree
  - feature.explain
  - feature.hybrid_search
  - security.os_tenant_assert
- `.env.example`

## Acceptance Criteria
- secrets 없이도 로컬 구동 가능

