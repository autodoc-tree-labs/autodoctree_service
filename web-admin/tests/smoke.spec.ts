import { test, expect } from "@playwright/test";

test("renders admin login", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Admin Login" })).toBeVisible();
});
