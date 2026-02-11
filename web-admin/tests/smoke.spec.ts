import { test, expect } from "@playwright/test";

test("renders admin login", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /관리자 로그인|Admin Login/ })).toBeVisible();
});

test("loads tree debug page and renders neighbors table", async ({ page }) => {
  await page.route("**/api/v1/auth/login", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        access_token: "test-access-token",
        refresh_token: "test-refresh-token"
      })
    });
  });
  await page.route("**/api/v1/workspaces", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [{ id: "ws-1", name: "QA Workspace", role: "OWNER" }]
      })
    });
  });
  await page.route("**/api/v1/admin/tree/debug/cluster-stats", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        snapshot_id: "snap-1",
        status: "ACTIVE",
        cluster_count: 3,
        avg_cluster_size: 2.5,
        neighbor_edges_total: 10,
        edges_filtered_total: 4,
        label_filtered_total: 1,
        avg_label_length: 6.2,
        tree_rebuild_duration_ms: 120.3,
        moved_ratio: 0.1,
        churn_ratio: 0.1
      })
    });
  });
  await page.route("**/api/v1/admin/tree/debug/docs/**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        document_id: "doc-1",
        title_mask: { hash: "sha256:doc1", length: 11 },
        assignment: { node_id: "node-1", node_label: "billing", snapshot_id: "snap-1" },
        assignment_confidence: 0.42,
        neighbors: [
          {
            neighbor_doc_id: "doc-2",
            title_mask: { hash: "sha256:doc2", length: 21 },
            channel_scores: {
              semantic: 0.93,
              lexical: 0.71,
              final: 0.88
            },
            edge_decision: {
              lexical_gate_passed: true,
              reason: "EMBEDDING_LEXICAL_GATED",
              entity_overlap: 2,
              title_overlap: 1
            }
          }
        ],
        trace_id: "trace-doc",
        request_id: "req-doc"
      })
    });
  });
  await page.route("**/api/v1/admin/tree/debug/clusters/**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        cluster_id: "node-1",
        snapshot_id: "snap-1",
        label: "billing",
        member_count: 2,
        members: [
          {
            document_id: "doc-1",
            title_mask: { hash: "sha256:doc1", length: 11 },
            signals: ["CLUSTER_DEFAULT"]
          }
        ],
        exemplars: [
          {
            document_id: "doc-2",
            title_mask: { hash: "sha256:doc2", length: 21 },
            avg_similarity: 0.88
          }
        ],
        label_candidates: ["billing", "invoice"],
        trace_id: "trace-cluster",
        request_id: "req-cluster"
      })
    });
  });
  await page.route("**/api/v1/admin/tree/debug/rebuilds/**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        snapshot_id: "snap-1",
        status: "ACTIVE",
        created_at: "2026-02-11T00:00:00",
        parameters: { neighbor_top_k: 5 },
        models: { embedding_model: "bge-m3" },
        decision_summary: { moved_ratio: 0.1 },
        cluster_count: 2,
        membership_count: 3,
        unsorted_ratio: 0.0,
        stage_logs: [{ stage: "graph", duration_ms: 12.1, details: { edge_count: 10 } }],
        trace_id: "trace-rebuild",
        request_id: "req-rebuild"
      })
    });
  });

  await page.goto("/login");
  await page.getByPlaceholder("이메일 주소").fill("owner@autodoc.local");
  await page.getByPlaceholder("비밀번호").fill("password");
  await page.getByRole("button", { name: "로그인" }).click();

  await expect(page.getByRole("heading", { name: "워크스페이스 전환" })).toBeVisible();
  await page.getByRole("button", { name: "QA Workspace" }).click();
  await page.getByRole("link", { name: "트리 디버그" }).click();
  await expect(page.getByRole("heading", { name: "트리 디버그" })).toBeVisible();

  await page.getByPlaceholder("문서 식별자(document_id)").fill("doc-1");
  await page.getByRole("button", { name: "문서 조회" }).click();
  await expect(page.getByText("assignment: billing (node-1)")).toBeVisible();
  await expect(page.getByText("EMBEDDING_LEXICAL_GATED")).toBeVisible();

  await page.getByPlaceholder("클러스터 식별자(cluster_id)").fill("node-1");
  await page.getByRole("button", { name: "클러스터 조회" }).click();
  await expect(page.getByText("label_candidates: billing, invoice")).toBeVisible();

  await page.getByPlaceholder("스냅샷 식별자(snapshot_id)").fill("snap-1");
  await page.getByRole("button", { name: "리빌드 조회" }).click();
  await expect(page.getByText("\"neighbor_top_k\": 5")).toBeVisible();
});
