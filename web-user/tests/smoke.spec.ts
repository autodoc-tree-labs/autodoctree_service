import { test, expect } from "@playwright/test";

test("renders login form", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Login" })).toBeVisible();
});
