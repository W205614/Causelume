import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  use: {
    baseURL: "http://127.0.0.1:17643",
    launchOptions: { executablePath: process.env.CAUSELUME_BROWSER },
  },
  webServer: {
    command: "python -m http.server 17643 --bind 127.0.0.1 --directory public",
    url: "http://127.0.0.1:17643",
  },
});
