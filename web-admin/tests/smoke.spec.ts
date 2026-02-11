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
  await page.route("**/api/v1/admin/tree/debug/neighbors**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        document_id: "doc-1",
        title: "billing doc",
        neighbors: [
          {
            neighbor_doc_id: "doc-2",
            title: "billing invoice guide",
            sem_sim: 0.93,
            lex_sim: 0.71,
            entity_overlap: 2,
            final_sim: 0.88,
            gate_flags: {
              lexical_gate_passed: true,
              reason: "EMBEDDING_LEXICAL_GATED"
            }
          }
        ]
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
  await page.getByRole("button", { name: "이웃 조회" }).click();
  await expect(page.getByText("billing invoice guide")).toBeVisible();
  await expect(page.getByText("EMBEDDING_LEXICAL_GATED")).toBeVisible();
});
