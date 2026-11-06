// Run against a disposable Java database. Writes one work order and its timeline.
import { chromium, expect } from "@playwright/test";
const url = process.env.CAUSELUME_URL,
  password = process.env.CAUSELUME_ADMIN_PASSWORD;
if (!url || !password)
  throw Error(
    "Set CAUSELUME_URL and CAUSELUME_ADMIN_PASSWORD for the disposable acceptance database.",
  );
const browser = await chromium.launch({
  executablePath: process.env.CAUSELUME_BROWSER,
});
try {
  const page = await browser.newPage();
  await page.goto(url);
  await page.getByLabel("账号", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByText("业务工作区")).toBeVisible();
  await page.getByRole("button", { name: "创建工单" }).click();
  const title = "浏览器验收 " + Date.now();
  await page.getByLabel("事件标题").fill(title);
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: new RegExp(title) }),
  ).toBeVisible();
  const detailUrl = page.url();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: new RegExp(title) }),
  ).toBeVisible();
  for (const [action, field] of [
    ["分派", "处理说明"],
    ["开始处理", "处理说明"],
    ["验证恢复", "恢复验证依据"],
    ["关闭并复盘", "复盘记录"],
  ]) {
    await page.getByRole("button", { name: action, exact: true }).click();
    await page
      .getByLabel(field, { exact: true })
      .fill(action + " · 浏览器验收记录");
    await page.getByRole("button", { name: "确认", exact: true }).click();
  }
  await expect(page.getByText("已关闭", { exact: true })).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("link", { name: "用户管理" })
    .click();
  await expect(page.getByRole("heading", { name: "操作审计" })).toBeVisible();
  await page.goto(detailUrl);
  await expect(
    page.getByText("关闭并复盘 · 浏览器验收记录", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(
    page.getByRole("heading", { name: "登录运维控制台" }),
  ).toBeVisible();
  console.log(
    "PASS: live login, session reload, ticket lifecycle, persisted review, administrative audit, logout",
  );
} finally {
  await browser.close();
}
