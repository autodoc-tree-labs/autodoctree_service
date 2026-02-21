import { expect, test, type Page } from "@playwright/test";

const WORKSPACES = [
  { id: "ws-1", name: "Personal", role: "OWNER" },
  { id: "ws-2", name: "Research", role: "OWNER" }
];

const initialDocumentsByWorkspace = () => ({
  "ws-1": [
    {
      id: "doc-1",
      title: "코난 전기",
      status: "READY",
      parent_document_id: null,
      pipeline_status: { ingest: "DONE", embed: "DONE", index: "DONE", tree: "DONE" },
      attachments: [],
      updated_at: "2026-02-19T09:00:00Z",
      body_markdown: "# 코난 전기\n\n본문",
      version: 1
    },
    {
      id: "doc-1-child",
      title: "코난 전기 하위",
      status: "READY",
      parent_document_id: "doc-1",
      pipeline_status: { ingest: "DONE", embed: "DONE", index: "DONE", tree: "DONE" },
      attachments: [],
      updated_at: "2026-02-19T08:50:00Z",
      body_markdown: "",
      version: 1
    },
    {
      id: "doc-2",
      title: "녹차의 효능",
      status: "READY",
      parent_document_id: null,
      pipeline_status: { ingest: "DONE", embed: "DONE", index: "DONE", tree: "DONE" },
      attachments: [],
      updated_at: "2026-02-19T08:40:00Z",
      body_markdown: "",
      version: 1
    }
  ],
  "ws-2": [
    {
      id: "ws2-doc-1",
      title: "도쿄 비즈니스 예절",
      status: "READY",
      parent_document_id: null,
      pipeline_status: { ingest: "DONE", embed: "DONE", index: "DONE", tree: "DONE" },
      attachments: [],
      updated_at: "2026-02-19T09:10:00Z",
      body_markdown: "# 도쿄",
      version: 1
    }
  ]
});

const initialTrashByWorkspace = () => ({
  "ws-1": [
    {
      id: "trash-1",
      title: "삭제된 문서 샘플",
      status: "DELETED",
      parent_document_id: null,
      pipeline_status: { ingest: "DONE", embed: "DONE", index: "DONE", tree: "DONE" },
      attachments: [],
      updated_at: "2026-02-19T07:20:00Z",
      body_markdown: "",
      version: 1
    }
  ],
  "ws-2": []
});

const TREE_RESPONSE = {
  snapshot_id: "snap-1",
  status: "ACTIVE",
  view_type: "topic",
  nodes: [
    {
      id: "node-1",
      parent_id: null,
      label: "AutoDoc",
      locked: false,
      documents: ["doc-1", "doc-2"],
      document_summaries: [
        { id: "doc-1", title: "코난 전기" },
        { id: "doc-2", title: "녹차의 효능" }
      ]
    }
  ]
};

const initialFavoritesByWorkspace = () => ({
  "ws-1": ["doc-1"],
  "ws-2": []
});

async function mockAuthenticatedSession(page: Page) {
  await page.addInitScript(() => {
    window.sessionStorage.setItem(
      "autodoc.user.session.v1",
      JSON.stringify({
        accessToken: "test-access",
        refreshToken: "test-refresh",
        workspaceId: "ws-1",
        workspaceName: "Personal"
      })
    );
    window.localStorage.setItem("autodoc.user.last-workspace.v1", "ws-1");
  });
}

async function mockApi(page: Page) {
  const documentsByWorkspace = initialDocumentsByWorkspace();
  const trashByWorkspace = initialTrashByWorkspace();
  const favoritesByWorkspace = initialFavoritesByWorkspace();

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const method = request.method();
    const url = new URL(request.url());
    const path = url.pathname.replace("/api/v1", "");
    const workspaceId = request.headers()["x-workspace-id"] ?? "ws-1";

    if (method === "GET" && path === "/workspaces") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: WORKSPACES }) });
      return;
    }

    if (method === "GET" && path === "/documents") {
      const items = documentsByWorkspace[workspaceId] ?? [];
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items, page: 0, size: 20, total: items.length }) });
      return;
    }

    if (method === "GET" && path === "/documents/trash") {
      const items = trashByWorkspace[workspaceId] ?? [];
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items, page: 0, size: 20, total: items.length }) });
      return;
    }

    if (method === "GET" && path === "/documents/favorites") {
      const favoriteIds = favoritesByWorkspace[workspaceId] ?? [];
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          items: favoriteIds.map((documentId) => ({ document_id: documentId, created_at: "2026-02-19T10:00:00Z" })),
          total: favoriteIds.length
        })
      });
      return;
    }

    const favoritePathMatch = path.match(/^\/documents\/([^/]+)\/favorite$/);
    if (favoritePathMatch && (method === "POST" || method === "DELETE")) {
      const documentId = favoritePathMatch[1];
      const favorites = new Set(favoritesByWorkspace[workspaceId] ?? []);
      if (method === "POST") {
        favorites.add(documentId);
      } else {
        favorites.delete(documentId);
      }
      favoritesByWorkspace[workspaceId] = Array.from(favorites);
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    if (method === "GET" && path.startsWith("/documents/")) {
      const documentId = path.split("/")[2] ?? "";
      const items = [...(documentsByWorkspace["ws-1"] ?? []), ...(documentsByWorkspace["ws-2"] ?? [])];
      const document = items.find((item) => item.id === documentId);
      if (!document) {
        await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(document) });
      return;
    }

    if (method === "PATCH" && path.startsWith("/documents/")) {
      const documentId = path.split("/")[2] ?? "";
      const payload = JSON.parse(request.postData() ?? "{}") as { title?: string; body_markdown?: string };
      const items = documentsByWorkspace[workspaceId] ?? [];
      const target = items.find((item) => item.id === documentId);
      if (!target) {
        await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) });
        return;
      }
      if (typeof payload.title === "string") {
        target.title = payload.title;
      }
      if (typeof payload.body_markdown === "string") {
        target.body_markdown = payload.body_markdown;
      }
      target.version = Number(target.version ?? 0) + 1;
      target.updated_at = "2026-02-19T10:01:00Z";
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    if (method === "POST" && path === "/documents") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ id: "doc-created" }) });
      return;
    }

    const movePathMatch = path.match(/^\/documents\/([^/]+)\/move$/);
    if (movePathMatch && method === "POST") {
      const documentId = movePathMatch[1];
      const bodyRaw = request.postData() ?? "{}";
      const payload = JSON.parse(bodyRaw) as { parent_document_id?: string | null };
      const items = documentsByWorkspace[workspaceId] ?? [];
      const target = items.find((item) => item.id === documentId);
      if (!target) {
        await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) });
        return;
      }
      target.parent_document_id = payload.parent_document_id ?? null;
      target.updated_at = "2026-02-19T10:00:00Z";
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    const restorePathMatch = path.match(/^\/documents\/([^/]+)\/restore$/);
    if (restorePathMatch && method === "POST") {
      const documentId = restorePathMatch[1];
      const trashItems = trashByWorkspace[workspaceId] ?? [];
      const targetIndex = trashItems.findIndex((item) => item.id === documentId);
      if (targetIndex < 0) {
        await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) });
        return;
      }
      const [restored] = trashItems.splice(targetIndex, 1);
      restored.status = "PROCESSING";
      restored.updated_at = "2026-02-19T10:02:00Z";
      const docs = documentsByWorkspace[workspaceId] ?? [];
      docs.unshift(restored);
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    if (method === "DELETE" && path.startsWith("/documents/")) {
      const documentId = path.split("/")[2] ?? "";
      const docs = documentsByWorkspace[workspaceId] ?? [];
      const targetIndex = docs.findIndex((item) => item.id === documentId);
      if (targetIndex < 0) {
        await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) });
        return;
      }
      const [deleted] = docs.splice(targetIndex, 1);
      deleted.status = "DELETED";
      deleted.parent_document_id = null;
      deleted.updated_at = "2026-02-19T10:01:00Z";
      const trash = trashByWorkspace[workspaceId] ?? [];
      trash.unshift(deleted);
      favoritesByWorkspace[workspaceId] = (favoritesByWorkspace[workspaceId] ?? []).filter((value) => value !== documentId);
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    if (method === "GET" && path === "/trees") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(TREE_RESPONSE) });
      return;
    }

    if (method === "POST" && path === "/tree/rebuild") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ snapshot_id: "snap-2", status: "ACTIVE", pending_count: 0 })
      });
      return;
    }

    if (method === "GET" && path === "/questions") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [], open_count: 0 }) });
      return;
    }

    if (method === "GET" && path === "/search") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ items: [{ document_id: "doc-1", title: "코난 전기", score: 1.23 }] })
      });
      return;
    }

    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({}) });
  });
}

test.beforeEach(async ({ page }) => {
  await mockAuthenticatedSession(page);
  await mockApi(page);
});

test("renders workspace app shell and view navigation", async ({ page }) => {
  await page.goto("/w/ws-1");

  await expect(page.getByText("Quick Actions")).toBeVisible();
  await expect(page.getByText("Favorites")).toBeVisible();
  await expect(page.getByRole("button", { name: "★ 코난 전기", exact: true })).toBeVisible();
  await expect(page.getByText("Pages")).toBeVisible();
  await expect(page.getByText("Views")).toBeVisible();
  await expect(page.getByRole("button", { name: "Trash", exact: true })).toBeVisible();
  await expect(page.getByRole("tab", { name: "문서로 분류" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "노드로 분류" })).toBeVisible();

  await page.getByRole("button", { name: "Tree" }).click();
  await expect(page).toHaveURL(/\/w\/ws-1\/view\/tree/);
  await expect(page.getByRole("heading", { name: "트리" })).toBeVisible();
});

test("opens command palette and jumps to document editor", async ({ page }) => {
  await page.goto("/w/ws-1");

  await page.keyboard.press("Control+K");
  await expect(page.getByPlaceholder("문서 검색 또는 명령 입력...")).toBeVisible();
  await page.getByPlaceholder("문서 검색 또는 명령 입력...").fill("코난 전기");
  await page.getByRole("button", { name: "코난 전기 페이지 열기" }).click();

  await expect(page).toHaveURL(/\/w\/ws-1\/doc\/doc-1/);
  await expect(page.getByLabel("제목")).toHaveValue("코난 전기");
  await expect(page.getByText("모든 변경사항이 저장되었습니다.")).toBeVisible();
});

test("switching workspace updates route and page tree", async ({ page }) => {
  await page.goto("/w/ws-1");
  await expect(page.getByRole("button", { name: /코난 전기/ })).toBeVisible();

  await page.selectOption("#workspace-switcher", "ws-2");
  await expect(page).toHaveURL(/\/w\/ws-2/);
  await expect(page.getByRole("button", { name: "• 도쿄 비즈니스 예절", exact: true })).toBeVisible();
});

test("page row hover shows quick actions and context menu", async ({ page }) => {
  await page.goto("/w/ws-1");

  const targetPage = page
    .locator(".sidebar-section-pages")
    .getByRole("button", { name: "• ★ 코난 전기", exact: true });
  await targetPage.hover();

  const menuButton = page.getByRole("button", { name: "페이지 메뉴" }).first();
  await expect(menuButton).toBeVisible();
  await menuButton.click();

  await expect(page.getByRole("menuitem", { name: "문서 수정" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "하위 페이지 추가" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: /즐겨찾기/ })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "이름 바꾸기" })).toBeVisible();
});

test("inline rename from page menu updates title without browser prompt", async ({ page }) => {
  await page.goto("/w/ws-1");

  const targetRow = page.locator(".sidebar-page-row", {
    has: page.getByRole("button", { name: "• 녹차의 효능", exact: true })
  });
  await targetRow.hover();
  await targetRow.getByRole("button", { name: "페이지 메뉴" }).click();

  await targetRow.getByRole("menuitem", { name: "이름 바꾸기" }).click();
  const renameInput = targetRow.getByLabel("새 페이지 이름");
  await expect(renameInput).toBeVisible();
  await renameInput.fill("녹차 효능 정리");
  await targetRow.getByRole("button", { name: "저장", exact: true }).click();

  await expect(page.getByText("페이지 이름을 변경했습니다.")).toBeVisible();
  await expect(page.getByRole("button", { name: "• 녹차 효능 정리", exact: true })).toBeVisible();
});

test("favorite toggle from page menu updates favorites section", async ({ page }) => {
  await page.goto("/w/ws-1");

  const targetRow = page.locator(".sidebar-page-row", {
    has: page.getByRole("button", { name: "• 녹차의 효능", exact: true })
  });
  await targetRow.hover();
  await targetRow.getByRole("button", { name: "페이지 메뉴" }).click();
  await targetRow.getByRole("menuitem", { name: "즐겨찾기에 추가" }).click();

  await expect(page.getByRole("button", { name: "★ 녹차의 효능", exact: true })).toBeVisible();
});

test("drag and drop page row moves document under another document", async ({ page }) => {
  await page.goto("/w/ws-1");

  const source = page.locator(".sidebar-section-pages").getByRole("button", { name: "• 녹차의 효능", exact: true });
  const target = page.locator(".sidebar-section-pages").getByRole("button", { name: /코난 전기/ }).first();
  await expect(source).toHaveCSS("padding-left", "12px");

  await source.dragTo(target);

  await expect(source).toHaveCSS("padding-left", "28px");
});

test("trash view lists deleted documents and supports restore", async ({ page }) => {
  await page.goto("/w/ws-1/view/trash");

  await expect(page.getByRole("heading", { name: "휴지통" })).toBeVisible();
  await expect(page.getByText("삭제된 문서 샘플")).toBeVisible();

  await page.getByRole("button", { name: "복원", exact: true }).click();
  await expect(page.getByText("문서를 복원했습니다.")).toBeVisible();
  await expect(page.getByText("삭제된 문서 샘플")).toHaveCount(0);
});

test("mobile menu button opens fallback menu panel", async ({ page }) => {
  await page.setViewportSize({ width: 430, height: 860 });
  await page.goto("/w/ws-1");

  const menuButton = page.getByRole("button", { name: "메뉴", exact: true });
  await expect(menuButton).toBeVisible();
  await menuButton.click();

  const header = page.getByRole("banner");
  await expect(header.getByRole("button", { name: "Documents" })).toBeVisible();
  await expect(header.getByRole("button", { name: "Tree" })).toBeVisible();
  await expect(header.getByRole("button", { name: "Questions" })).toBeVisible();
  await expect(header.getByRole("button", { name: "Trash" })).toBeVisible();
});
