import { test, expect } from "@playwright/test";
import { fileURLToPath } from "node:url";
const destination = fileURLToPath(
  new URL("../../docs/images/", import.meta.url),
);
test("演示控制台在桌面、平板、手机宽度下可导航和阅读", async ({ page }) => {
  await page.goto("/?demo=1");
  await page.getByLabel("账号", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill("demo");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  const views = [
    [1440, "overview", "运维概览", "overview"],
    [1440, "tickets/1", "结算接口持续返回 503", "ticket-timeline"],
    [1440, "tasks/1", "调查 #1", "investigation"],
    [900, "overview", "运维概览", null],
    [390, "tasks/1", "调查 #1", "investigation-mobile"],
  ];
  for (const [width, path, title, image] of views) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
    await page.evaluate((hash) => (location.hash = hash), path);
    await expect(
      page.getByRole("heading", { name: new RegExp(title) }),
    ).toBeVisible();
    await expect(
      page.getByText("模拟数据", { exact: true }).first(),
    ).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(width + 1);
    if (process.env.CAUSELUME_CAPTURE_DEMO === "1" && image)
      await page.screenshot({
        path: destination + image + ".png",
        fullPage: true,
      });
  }
});
