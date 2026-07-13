import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  reporter: "line",
  webServer: {
    command: "npm run dev -- --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: false,
    timeout: 120_000,
  },
  use: {
    baseURL: "http://127.0.0.1:4173",
    browserName: "chromium",
    colorScheme: "dark",
    reducedMotion: "reduce",
    screenshot: "only-on-failure",
  },
});
