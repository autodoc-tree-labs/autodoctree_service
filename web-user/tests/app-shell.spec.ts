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
      documents: ["doc-1", "doc-2", "ghost-doc-uuid-1234"],
      document_summaries: [
        { id: "doc-1", title: "코난 전기" },
        { id: "doc-2", title: "녹차의 효능" },
        { id: "ghost-doc-uuid-1234", title: "ghost-doc-uuid-1234" }
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
  const pendingUploadsByAttachmentId = new Map<
    string,
    { workspaceId: string; documentId: string; filename: string; contentType: string; size: number }
  >();
  let attachmentSequence = 1;

  const collectSubtreeIds = (workspaceId: string, rootDocumentId: string): Set<string> => {
    const docs = documentsByWorkspace[workspaceId] ?? [];
    const childrenByParent = new Map<string, string[]>();
    for (const doc of docs) {
      const parentId = doc.parent_document_id;
      if (!parentId) {
        continue;
      }
      const children = childrenByParent.get(parentId) ?? [];
      children.push(doc.id);
      childrenByParent.set(parentId, children);
    }
    const ids = new Set<string>();
    const queue = [rootDocumentId];
    while (queue.length > 0) {
      const current = queue.shift();
      if (!current || ids.has(current)) {
        continue;
      }
      ids.add(current);
      for (const childId of childrenByParent.get(current) ?? []) {
        queue.push(childId);
      }
    }
    return ids;
  };

  await page.route("**/mock-upload/**", async (route) => {
    if (route.request().method() === "PUT") {
      await route.fulfill({ status: 200, body: "" });
      return;
    }
    await route.fallback();
  });

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

    if (method === "POST" && path === "/attachments/presign") {
      const payload = JSON.parse(request.postData() ?? "{}") as {
        document_id?: string;
        filename?: string;
        content_type?: string;
        size?: number;
      };
      const documentId = payload.document_id ?? "";
      if (!documentId) {
        await route.fulfill({ status: 400, contentType: "application/json", body: JSON.stringify({ message: "document_id required" }) });
        return;
      }
      const attachmentId = `att-upload-${attachmentSequence++}`;
      pendingUploadsByAttachmentId.set(attachmentId, {
        workspaceId,
        documentId,
        filename: payload.filename ?? "upload.bin",
        contentType: payload.content_type ?? "application/octet-stream",
        size: Number(payload.size ?? 0)
      });
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          attachment_id: attachmentId,
          upload_url: `http://localhost:4174/mock-upload/${attachmentId}`
        })
      });
      return;
    }

    if (method === "POST" && path === "/attachments/complete") {
      const payload = JSON.parse(request.postData() ?? "{}") as { attachment_id?: string };
      const attachmentId = payload.attachment_id ?? "";
      const pending = pendingUploadsByAttachmentId.get(attachmentId);
      if (!attachmentId || !pending) {
        await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) });
        return;
      }
      const workspaceDocs = documentsByWorkspace[pending.workspaceId] ?? [];
      const targetDocument = workspaceDocs.find((item) => item.id === pending.documentId);
      if (!targetDocument) {
        await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) });
        return;
      }
      const encodedFilename = encodeURIComponent(pending.filename);
      const nextAttachment = {
        id: attachmentId,
        filename: pending.filename,
        content_type: pending.contentType,
        size: pending.size,
        status: "UPLOADED",
        download_url: `http://localhost:59000/autodoc/workspaces/${pending.workspaceId}/attachments/${pending.documentId}/${encodedFilename}?X-Amz-Algorithm=AWS4-HMAC-SHA256`
      };
      targetDocument.attachments = [...targetDocument.attachments.filter((attachment) => attachment.id !== attachmentId), nextAttachment];
      targetDocument.updated_at = "2026-02-19T10:03:00Z";
      pendingUploadsByAttachmentId.delete(attachmentId);
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    const invitePathMatch = path.match(/^\/workspaces\/([^/]+)\/invites$/);
    if (invitePathMatch && method === "POST") {
      const inviteWorkspaceId = invitePathMatch[1];
      const payload = JSON.parse(request.postData() ?? "{}") as { email?: string; role?: string };
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          invite_token: `invite-${inviteWorkspaceId}-${payload.role ?? "MEMBER"}-${(payload.email ?? "member").replace(/[^a-zA-Z0-9]/g, "")}`
        })
      });
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
      if (!docs.some((item) => item.id === documentId)) {
        await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "not found" }) });
        return;
      }
      const subtreeIds = collectSubtreeIds(workspaceId, documentId);
      const deleted: Array<(typeof docs)[number]> = [];
      for (let index = docs.length - 1; index >= 0; index -= 1) {
        const candidate = docs[index];
        if (!subtreeIds.has(candidate.id)) {
          continue;
        }
        const [removed] = docs.splice(index, 1);
        removed.status = "DELETED";
        removed.parent_document_id = null;
        removed.updated_at = "2026-02-19T10:01:00Z";
        deleted.unshift(removed);
      }
      const trash = trashByWorkspace[workspaceId] ?? [];
      trash.unshift(...deleted);
      favoritesByWorkspace[workspaceId] = (favoritesByWorkspace[workspaceId] ?? []).filter((value) => !subtreeIds.has(value));
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    if (method === "GET" && path === "/trees") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(TREE_RESPONSE) });
      return;
    }

    if (method === "GET" && path === "/tree/rebuild/status") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ status: "IDLE", pending_count: 0, view_type: "topic" })
      });
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

test("saving page title in editor updates sidebar page tree immediately", async ({ page }) => {
  await page.goto("/w/ws-1/doc/doc-1");

  const pagesSectionTitles = page.locator(".sidebar-section-pages .sidebar-page-title");
  await expect(pagesSectionTitles.filter({ hasText: /^코난 전기$/ })).toHaveCount(1);

  await page.getByLabel("제목").fill("코난 전기 개정판");
  await page.getByRole("button", { name: "저장", exact: true }).click();

  await expect(page.getByText("문서를 저장했습니다.")).toBeVisible();
  await expect(pagesSectionTitles.filter({ hasText: /^코난 전기 개정판$/ })).toHaveCount(1);
  await expect(pagesSectionTitles.filter({ hasText: /^코난 전기$/ })).toHaveCount(0);
});

test("editor page supports left and right panel arrow toggles with correct defaults", async ({ page }) => {
  await page.goto("/w/ws-1/doc/doc-1");

  await expect(page.locator(".workspace-layout")).not.toHaveClass(/is-left-sidebar-collapsed/);
  await expect(page.locator(".workspace-sidebar")).toBeVisible();
  await expect(page.locator("#workspace-doc-context-panel")).toHaveCount(0);

  await page.getByRole("button", { name: "우측 패널 열기" }).click();
  await expect(page.locator("#workspace-doc-context-panel")).toBeVisible();
  await page.getByRole("button", { name: "우측 패널 닫기" }).click();
  await expect(page.locator("#workspace-doc-context-panel")).toHaveCount(0);

  await page.getByRole("button", { name: "좌측 패널 닫기" }).click();
  await expect(page.locator(".workspace-layout")).toHaveClass(/is-left-sidebar-collapsed/);
  await page.getByRole("button", { name: "좌측 패널 열기" }).click();
  await expect(page.locator(".workspace-layout")).not.toHaveClass(/is-left-sidebar-collapsed/);
});

test("opens image block document without tiptap mount crash", async ({ page }) => {
  await page.route("**/api/v1/documents/doc-1", async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "doc-1",
        title: "코난 전기",
        status: "READY",
        parent_document_id: null,
        pipeline_status: { ingest: "DONE", embed: "DONE", index: "DONE", tree: "DONE" },
        attachments: [
          {
            id: "att-1",
            filename: "sample.png",
            content_type: "image/png",
            size: 1234,
            status: "UPLOADED",
            download_url:
              "http://localhost:59000/autodoc/workspaces/ws-1/attachments/doc-1/sample.png?X-Amz-Algorithm=AWS4-HMAC-SHA256"
          }
        ],
        updated_at: "2026-02-19T09:00:00Z",
        body_markdown: "![sample](http://localhost:59000/autodoc/workspaces/ws-1/attachments/doc-1/sample.png)",
        blocks_json: {
          type: "doc",
          content: [
            {
              type: "image",
              attrs: {
                src: "http://localhost:59000/autodoc/workspaces/ws-1/attachments/doc-1/sample.png",
                alt: "sample.png",
                width: "200%",
                attachmentId: "att-1",
                filename: "sample.png",
                mimeType: "image/png",
                size: 1234
              }
            },
            {
              type: "paragraph",
              content: [{ type: "text", text: "이미지 테스트 문서" }]
            }
          ]
        },
        version: 1
      })
    });
  });

  await page.goto("/w/ws-1/doc/doc-1");
  await expect(page.getByLabel("제목")).toHaveValue("코난 전기");
  await expect(page.getByText("모든 변경사항이 저장되었습니다.")).toBeVisible();
  await expect(page.locator(".error-panel")).toHaveCount(0);
  await expect(page.locator(".editor-v2-image-node").first()).toHaveAttribute("style", /width:\s*100%/i);
  await expect(page.locator(".editor-v2-image-node img").first()).toHaveAttribute(
    "src",
    /X-Amz-Algorithm=AWS4-HMAC-SHA256/
  );
});

test("drag and drop upload inserts file block without upload button click", async ({ page }) => {
  await page.goto("/w/ws-1/doc/doc-1");
  await expect(page.getByLabel("제목")).toHaveValue("코난 전기");

  const dataTransfer = await page.evaluateHandle(() => {
    const dt = new DataTransfer();
    dt.items.add(new File(["drag-drop payload"], "drag-note.txt", { type: "text/plain" }));
    return dt;
  });

  const editorSurface = page.locator(".editor-v2-prosemirror");
  await editorSurface.dispatchEvent("drop", { dataTransfer });

  await expect(page.locator(".editor-v2-file a").first()).toHaveText("drag-note.txt");
  await dataTransfer.dispose();
});

test("drag and drop upload on editor surface keeps app stable and inserts attachment", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => {
    pageErrors.push(error.message);
  });

  await page.goto("/w/ws-1/doc/doc-1");
  await expect(page.getByLabel("제목")).toHaveValue("코난 전기");

  const dataTransfer = await page.evaluateHandle(() => {
    const dt = new DataTransfer();
    dt.items.add(new File(["drag-drop payload"], "drag-surface.txt", { type: "text/plain" }));
    return dt;
  });

  const editorSurface = page.locator(".editor-v2-surface");
  await editorSurface.dispatchEvent("dragenter", { dataTransfer });
  await editorSurface.dispatchEvent("dragover", { dataTransfer });
  await editorSurface.dispatchEvent("drop", { dataTransfer });

  await expect(page.locator(".editor-v2-file a").first()).toHaveText("drag-surface.txt");
  expect(pageErrors).toEqual([]);
  await dataTransfer.dispose();
});

test("slash menu emoji item opens picker and inserts selected emoji", async ({ page }) => {
  await page.goto("/w/ws-1/doc/doc-1");

  const editorSurface = page.locator(".editor-v2-prosemirror");
  await editorSurface.click();
  await page.keyboard.press("End");
  await page.keyboard.press("Enter");
  await page.keyboard.type("/emoji");

  const slashMenu = page.locator(".editor-v2-slash");
  await expect(slashMenu).toBeVisible();
  const emojiSlashItem = slashMenu.getByRole("button", { name: /Emoji/i }).first();
  await expect(emojiSlashItem).toBeVisible();
  await emojiSlashItem.click();

  const emojiPicker = page.locator(".editor-v2-emoji-picker");
  await expect(emojiPicker).toBeVisible();
  await emojiPicker.getByRole("button", { name: "Fire 🔥" }).click();
  await expect(editorSurface).toContainText("🔥");
});

test("slash menu arrow navigation auto-scrolls to keep active item visible", async ({ page }) => {
  await page.goto("/w/ws-1/doc/doc-1");

  const editorSurface = page.locator(".editor-v2-prosemirror");
  await editorSurface.click();
  await page.keyboard.press("End");
  await page.keyboard.press("Enter");
  await page.keyboard.type("/");

  const slashMenu = page.locator(".editor-v2-slash");
  await expect(slashMenu).toBeVisible();
  const initialScrollTop = await slashMenu.evaluate((element) => element.scrollTop);

  for (let index = 0; index < 20; index += 1) {
    await page.keyboard.press("ArrowDown");
  }

  await expect
    .poll(async () => slashMenu.evaluate((element) => element.scrollTop), { timeout: 5000 })
    .toBeGreaterThan(initialScrollTop);
});

test("switching workspace updates route and page tree", async ({ page }) => {
  await page.goto("/w/ws-1");
  await expect(page.getByRole("button", { name: "• ★ 코난 전기", exact: true })).toBeVisible();

  await page.getByRole("button", { name: "워크스페이스 메뉴 열기" }).click();
  await page.getByRole("button", { name: "워크스페이스 전환 Research" }).click();
  await expect(page).toHaveURL(/\/w\/ws-2/);
  await expect(page.getByRole("button", { name: "• 도쿄 비즈니스 예절", exact: true })).toBeVisible();
});

test("workspace launcher shows settings, invite, and logout actions", async ({ page }) => {
  await page.goto("/w/ws-1");

  await page.getByRole("button", { name: "워크스페이스 메뉴 열기" }).click();
  await expect(page.getByRole("button", { name: "설정" })).toBeVisible();
  await expect(page.getByRole("button", { name: "멤버 초대" })).toBeVisible();
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();

  await page.getByRole("button", { name: "멤버 초대" }).click();
  await page.getByLabel("초대 이메일").fill("invitee@example.com");
  await page.getByLabel("권한").selectOption("VIEWER");
  await page.getByRole("button", { name: "초대 링크 생성" }).click();
  await expect(page.getByText("멤버 초대 토큰을 생성했습니다.")).toBeVisible();
  await expect(page.locator(".workspace-launcher-token code")).toContainText("invite-ws-1-VIEWER");
});

test("sidebar popovers stay inside the sidebar width when resized narrow", async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem("autodoc.workspace.sidebar-width.by-workspace.v1", JSON.stringify({ "ws-1": 220 }));
  });

  await page.goto("/w/ws-1");

  const sidebar = page.locator(".workspace-sidebar");
  const sidebarBounds = await sidebar.boundingBox();
  expect(sidebarBounds).not.toBeNull();

  await page.getByRole("button", { name: "워크스페이스 메뉴 열기" }).click();
  const launcherPopover = page.locator(".workspace-launcher-popover");
  await expect(launcherPopover).toBeVisible();
  const launcherBounds = await launcherPopover.boundingBox();
  expect(launcherBounds).not.toBeNull();
  expect(launcherBounds!.x).toBeGreaterThanOrEqual(sidebarBounds!.x - 1);
  expect(launcherBounds!.x + launcherBounds!.width).toBeLessThanOrEqual(sidebarBounds!.x + sidebarBounds!.width + 1);

  await page.getByRole("button", { name: "워크스페이스 메뉴 열기" }).click();
  const targetRow = page.locator(".sidebar-page-row", {
    has: page.getByRole("button", { name: "• ★ 코난 전기", exact: true })
  });
  await targetRow.hover();
  await targetRow.getByRole("button", { name: "페이지 메뉴" }).click();
  const pageMenu = targetRow.locator(".sidebar-page-menu");
  await expect(pageMenu).toBeVisible();
  const pageMenuBounds = await pageMenu.boundingBox();
  expect(pageMenuBounds).not.toBeNull();
  expect(pageMenuBounds!.x).toBeGreaterThanOrEqual(sidebarBounds!.x - 1);
  expect(pageMenuBounds!.x + pageMenuBounds!.width).toBeLessThanOrEqual(sidebarBounds!.x + sidebarBounds!.width + 1);
});

test("tree rebuild status notice remains visible after refresh from cached status", async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem(
      "autodoc.tree.rebuild-status.by_workspace.v1",
      JSON.stringify({
        "ws-1": {
          status: "QUEUED",
          pending_count: 2,
          view_type: "topic",
          cached_at: new Date().toISOString()
        }
      })
    );
  });

  await page.route("**/api/v1/tree/rebuild/status**", async (route) => {
    await route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({ error: { code: "INTERNAL_ERROR", message: "Unexpected error" } })
    });
  });

  await page.goto("/w/ws-1/view/tree");
  await expect(page.getByText("재빌드 요청이 처리 대기 중입니다 (대기 2건).")).toBeVisible();

  await page.reload();
  await expect(page.getByText("재빌드 요청이 처리 대기 중입니다 (대기 2건).")).toBeVisible();
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

test("deleting parent page from sidebar also removes child pages", async ({ page }) => {
  await page.goto("/w/ws-1");

  const pagesTitles = page.locator(".sidebar-section-pages .sidebar-page-title");
  await expect(pagesTitles.filter({ hasText: /^코난 전기$/ })).toHaveCount(1);
  await expect(pagesTitles.filter({ hasText: /^코난 전기 하위$/ })).toHaveCount(1);

  const parentRow = page.locator(".sidebar-page-row").filter({
    has: page.locator(".sidebar-page-title", { hasText: /^코난 전기$/ })
  });
  await parentRow.first().hover();
  await parentRow.first().getByRole("button", { name: "페이지 메뉴" }).click();
  page.once("dialog", (dialog) => dialog.accept());
  await parentRow.first().getByRole("menuitem", { name: "휴지통으로 이동" }).click();

  await expect(page.getByText("페이지를 삭제했습니다.")).toBeVisible();
  await expect(pagesTitles.filter({ hasText: /^코난 전기$/ })).toHaveCount(0);
  await expect(pagesTitles.filter({ hasText: /^코난 전기 하위$/ })).toHaveCount(0);
});

test("node browse mode hides stale tree memberships without live documents", async ({ page }) => {
  await page.goto("/w/ws-1");
  await page.getByRole("tab", { name: "노드로 분류" }).click();
  await expect(page.locator(".sidebar-node-label", { hasText: "AutoDoc" })).toBeVisible();
  await expect(page.locator(".sidebar-node-doc-title", { hasText: "코난 전기" })).toHaveCount(1);
  await expect(page.locator(".sidebar-node-doc-title", { hasText: "ghost-doc-uuid-1234" })).toHaveCount(0);
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
