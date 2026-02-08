# I-0101 — Monorepo layout bootstrap (services/web-user/web-admin)


## Goal
모노레포 구조를 확정하고 로컬 실행 진입점을 만든다.

## Scope
- Root:
  - `pnpm-workspace.yaml`, root `package.json` (workspaces include web-user/web-admin/packages)
  - `docker-compose.yml` (stub ok)
  - `.editorconfig`, `.gitignore` 기본
- `services/`:
  - Gradle multi-module 루트(`services/settings.gradle.kts`, `services/build.gradle.kts`)
  - 모듈 자리만 만들기: doc-api, workers, libs/common, libs/contracts
- `web-user/`, `web-admin/`:
  - Vite + React + TS 스캐폴드
  - dev port 고정: user 5174, admin 5173
- Root README/DEV_SETUP에 경로 명시

## Acceptance Criteria
- repo root를 열면 구조가 명확
- `pnpm -w install` 가능
- `pnpm -C web-user dev --port 5174`, `pnpm -C web-admin dev --port 5173` 실행 가능(placeholder UI)
- `./gradlew -p services tasks` 실행 가능(스캐폴드)

## Testing
- 최소 smoke: web build, services gradle configuration passes

## Observability
- N/A

## Rollout/Rollback
- N/A

