# DEV_SETUP

## Prerequisites
- Java 17
- Node 20+
- pnpm 10+
- Docker Desktop

## 1. Start local infra
```bash
docker compose up -d
docker compose ps
```

If default ports are already in use, run with overrides:
```bash
POSTGRES_PORT=55432 OPENSEARCH_PORT=59201 REDIS_PORT=56380 MINIO_PORT=59010 MINIO_CONSOLE_PORT=59011 docker compose up -d
```

Services:
- Postgres: `localhost:5432`
- OpenSearch: `localhost:59200`
- Redis: `localhost:56379`
- MinIO API: `localhost:59000`
- MinIO Console: `localhost:59001`

## 2. Backend (IntelliJ)
1. Open `services/` as Gradle project.
2. Run `doc-api` main class: `com.autodoctree.api.DocApiApplicationKt`
3. Env:
- `SPRING_PROFILES_ACTIVE=local`
- `DOC_API_JWT_SECRET=dev-secret-change-me`
- MinIO를 기본 포트 외로 바꿨다면 `S3_ENDPOINT`를 같은 주소로 지정 (예: `http://localhost:59010`)

Quick test:
```bash
curl -s http://localhost:8080/api/v1/health
```

## 3. Frontend
```bash
pnpm -w install
pnpm -C web-admin dev --port 5173
pnpm -C web-user dev --port 5174
```

## 4. HTTP smoke
Use IntelliJ HTTP Client files in `tools/http/`:
- `auth_and_doc_flow.http`
- `tenant_negative.http`

## 5. Feature flags
Default local flags are in `services/doc-api/src/main/resources/application-local.yml`.
Important flags:
- `feature.auto-tree`
- `feature.explain`
- `feature.hybrid-search`
- `security.os-tenant-assert`
- `TREE_NEIGHBOR_MIN_SIMILARITY` (기본 `0.8`, 값이 높을수록 트리 노드가 더 잘 분리됨)

## 6. Security notes
- Do not log document body, extracted text, chunk text, or presigned URLs.
- Tenant-scoped endpoints require `X-Workspace-Id`.
