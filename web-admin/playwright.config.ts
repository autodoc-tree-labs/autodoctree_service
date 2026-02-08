import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  testDir: "./tests",
  use: {
    baseURL: "http://localhost:5173"
  },
  webServer: {
    command: "pnpm dev",
    cwd: __dirname,
    port: 5173,
    reuseExistingServer: true,
    timeout: 120000
  }
});
