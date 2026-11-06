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
    if (action === "验证恢复") await page.getByLabel("恢复检测结果", { exact: true }).selectOption("PASSED");
    if (action === "关闭并复盘") {
      await page.getByLabel("影响范围", { exact: true }).fill("隔离验收服务");
      await page.getByLabel("故障开始时间", { exact: true }).fill("2026-11-06T10:00");
      await page.getByLabel("恢复时间", { exact: true }).fill("2026-11-06T10:05");
      await page.getByLabel("原因或尚未确定的原因", { exact: true }).fill("验收模拟故障");
      await page.getByLabel("不确定性与证据缺口", { exact: true }).fill("仅验证测试数据");
    }
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
  if (process.env.CAUSELUME_VERIFY_EVIDENCE === "1") {
    await page
      .getByRole("navigation")
      .getByRole("link", { name: "Agent 调查" })
      .click();
    await page
      .getByRole("row")
      .filter({ hasText: "已完成" })
      .first()
      .getByRole("link")
      .first()
      .click();
    for (const source of [
      "checkout:/metrics",
      "checkout:/logs",
      "验收脚本已核对日志与指标",
    ]) {
      await expect(page.getByText(source, { exact: true })).toBeVisible();
    }
    await page
      .getByRole("navigation")
      .getByRole("link", { name: "服务与资源" })
      .click();
    await page.getByRole("link", { name: "查看日志与指标" }).click();
    await expect(page.getByRole("heading", { name: "请求指标" })).toBeVisible();
    await expect(
      page.getByRole("row").filter({ hasText: "checkout_ok" }).first(),
    ).toBeVisible();
    console.log(
      "PASS: service metrics, observed recovery request, stored investigation evidence and feedback",
    );
  }
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
