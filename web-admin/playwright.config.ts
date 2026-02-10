import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  testDir: "./tests",
  use: {
    baseURL: "http://localhost:4173"
  },
  webServer: {
    command: "pnpm exec vite --port 4173",
    cwd: __dirname,
    port: 4173,
    reuseExistingServer: false,
    timeout: 120000
  }
});
