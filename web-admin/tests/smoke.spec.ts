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
        auto_ratio: 0.6,
        recommend_ratio: 0.2,
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
  await page.route("**/api/v1/admin/tree/policy", async (route) => {
    if (route.request().method() === "PATCH") {
      const body = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          workspace_id: "ws-1",
          auto_threshold: body.auto_threshold ?? 0.9,
          recommend_threshold: body.recommend_threshold ?? 0.7,
          quarantine_enabled: body.quarantine_enabled ?? true,
          reranker_enabled: body.reranker_enabled ?? false,
          source: "OVERRIDE",
          updated_by: "user-1",
          updated_at: "2026-02-11T00:00:00"
        })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        workspace_id: "ws-1",
        auto_threshold: 0.9,
        recommend_threshold: 0.7,
        quarantine_enabled: true,
        reranker_enabled: false,
        source: "OVERRIDE",
        updated_by: "user-1",
        updated_at: "2026-02-11T00:00:00"
      })
    });
  });
  await page.route("**/api/v1/tree/active", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        snapshot_id: "snap-1",
        status: "ACTIVE",
        nodes: [
          { id: "root", parent_id: null, label: "AutoDoc", locked: false },
          { id: "node-1", parent_id: "root", label: "billing", locked: false },
          { id: "node-2", parent_id: "root", label: "infra", locked: false }
        ]
      })
    });
  });
  const rules = [
    {
      id: "rule-1",
      rule_type: "TITLE_CONTAINS",
      rule_value: "invoice",
      rule_effect: "HARD",
      node_id: "node-1",
      node_label: "billing",
      enabled: true,
      created_at: "2026-02-11T00:00:00"
    }
  ];
  await page.route("**/api/v1/admin/tree/rules", async (route) => {
    const method = route.request().method();
    if (method === "POST") {
      const body = route.request().postDataJSON() as Record<string, string>;
      rules.push({
        id: "rule-2",
        rule_type: (body.rule_type ?? "TITLE_CONTAINS").toString(),
        rule_value: (body.rule_value ?? "").toString(),
        rule_effect: (body.rule_effect ?? "HARD").toString(),
        node_id: (body.node_id ?? "node-1").toString(),
        node_label: "billing",
        enabled: true,
        created_at: "2026-02-11T00:00:00"
      });
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          id: "rule-2",
          rule_type: body.rule_type ?? "TITLE_CONTAINS",
          rule_value: body.rule_value ?? "",
          rule_effect: body.rule_effect ?? "HARD",
          node_id: body.node_id ?? "node-1"
        })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: rules })
    });
  });
  await page.route("**/api/v1/admin/tree/rules/*", async (route) => {
    const method = route.request().method();
    const path = route.request().url().split("/").pop() ?? "";
    if (path === "preview" && method === "POST") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          document_id: "doc-1",
          rule_type: "SOURCE_TYPE",
          rule_value: "editor",
          rule_effect: "SOFT",
          matched: true,
          target_node_id: "node-1",
          target_node_label: "billing"
        })
      });
      return;
    }
    if (method === "PATCH") {
      const body = route.request().postDataJSON() as Record<string, string>;
      const target = rules.find((rule) => rule.id === path);
      if (target) {
        target.rule_type = (body.rule_type ?? target.rule_type).toString();
        target.rule_value = (body.rule_value ?? target.rule_value).toString();
        target.rule_effect = (body.rule_effect ?? target.rule_effect).toString();
        target.node_id = (body.node_id ?? target.node_id).toString();
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          id: path,
          rule_type: body.rule_type ?? "TITLE_CONTAINS",
          rule_value: body.rule_value ?? "",
          rule_effect: body.rule_effect ?? "HARD",
          node_id: body.node_id ?? "node-1"
        })
      });
      return;
    }
    if (method === "DELETE") {
      const index = rules.findIndex((rule) => rule.id === path);
      if (index >= 0) {
        rules.splice(index, 1);
      }
      await route.fulfill({ status: 204, body: "" });
      return;
    }
    await route.fulfill({ status: 404, body: "" });
  });
  let questionControlEnabled = true;
  let questionOpenCount = 2;
  let questionAnsweredCount = 5;
  let questionExpiredCount = 1;
  const questionItems = [
    {
      id: "q-1",
      question_type: "DOC_CLUSTER_CHOICE",
      status: "OPEN",
      document_id: "doc-1",
      impact_score: 0.78,
      payload: {
        option_a: { node_id: "node-1", label: "billing", score: 0.81 },
        option_b: { node_id: "node-2", label: "infra", score: 0.72 }
      },
      created_at: "2026-02-11T00:00:00"
    }
  ];
  await page.route("**/api/v1/admin/tree/questions/analytics", async (route) => {
    const denominator = questionAnsweredCount + questionExpiredCount;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        control: {
          enabled: questionControlEnabled,
          updated_by: "user-1",
          updated_at: "2026-02-11T00:00:00"
        },
        open_count: questionOpenCount,
        answered_count: questionAnsweredCount,
        expired_count: questionExpiredCount,
        answer_rate: denominator > 0 ? questionAnsweredCount / denominator : 0,
        avg_impact_open: 0.62,
        avg_impact_answered: 0.71,
        unsorted_ratio: 0.18,
        items: questionItems
      })
    });
  });
  await page.route("**/api/v1/admin/tree/questions/control", async (route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>;
    questionControlEnabled = Boolean(body.enabled);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        workspace_id: "ws-1",
        enabled: questionControlEnabled,
        updated_by: "user-1",
        updated_at: "2026-02-11T00:00:00"
      })
    });
  });
  await page.route("**/api/v1/admin/tree/questions/expire", async (route) => {
    const expired = questionOpenCount;
    questionOpenCount = 0;
    questionExpiredCount += expired;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ expired_count: expired })
    });
  });
  await page.route("**/api/v1/admin/tree/questions/generate", async (route) => {
    const generated = questionControlEnabled ? 2 : 0;
    questionOpenCount += generated;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ generated_count: generated })
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

  await page.getByRole("link", { name: "정책 설정" }).click();
  await expect(page.getByRole("heading", { name: "정책 설정" })).toBeVisible();
  await expect(page.getByText("\"source\": \"OVERRIDE\"")).toBeVisible();
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByText("정책이 저장되었습니다. 다음 리빌드부터 반영됩니다.")).toBeVisible();

  await page.getByRole("link", { name: "규칙 관리" }).click();
  await expect(page.getByRole("heading", { name: "규칙 관리" })).toBeVisible();
  await page.getByLabel("rule_type").selectOption("SOURCE_TYPE");
  await page.getByLabel("rule_value").fill("editor");
  await page.getByLabel("rule_effect").selectOption("SOFT");
  await page.getByPlaceholder("문서 식별자(document_id)").fill("doc-1");
  await page.getByRole("button", { name: "테스트" }).click();
  await expect(page.getByText("\"matched\": true")).toBeVisible();
  await page.getByRole("button", { name: "규칙 생성" }).click();
  await expect(page.getByText("규칙을 생성했습니다.")).toBeVisible();

  await page.getByRole("link", { name: "질문 트리아지" }).click();
  await expect(page.getByRole("heading", { name: "질문 트리아지" })).toBeVisible();
  await expect(page.getByText("q-1")).toBeVisible();

  await page.getByRole("button", { name: "질문 생성" }).click();
  await expect(page.getByText("질문 2건을 생성했습니다.")).toBeVisible();

  await page.getByLabel("질문 생성 활성화").uncheck();
  await page.getByRole("button", { name: "제어 저장" }).click();
  await expect(page.getByText("질문 생성 제어값을 저장했습니다.")).toBeVisible();

  await page.getByRole("button", { name: "열린 질문 만료" }).click();
  await expect(page.getByText("열린 질문 4건을 만료 처리했습니다.")).toBeVisible();
});
