import { expect, test } from "@playwright/test";
async function login(page, name = "admin") {
  await page.goto("/?demo=1");
  await page.getByLabel("账号", { exact: true }).fill(name);
  await page.getByLabel("密码", { exact: true }).fill("demo");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "运维概览" })).toBeVisible();
}
test("模拟登录、各主导航、过滤及权限状态", async ({ page }) => {
  await login(page, "operator");
  for (const name of [
    "服务与资源",
    "告警中心",
    "事件与工单",
    "Agent 调查",
    "用户管理",
  ]) {
    await page.getByRole("navigation").getByRole("link", { name }).click();
    await expect(
      page.getByRole("heading", { name, exact: true }),
    ).toBeVisible();
  }
  await expect(page.getByText("权限不足")).toBeVisible();
  await page.getByRole("link", { name: "事件与工单", exact: true }).click();
  await page.getByLabel("搜索工单").fill("no-match");
  await page.getByRole("button", { name: "筛选", exact: true }).click();
  await expect(page.getByText("暂无记录")).toBeVisible();
  await page.getByRole("button", { name: "退出登录" }).click();
  await page.getByLabel("账号", { exact: true }).fill("operator");
  await page.getByLabel("密码", { exact: true }).fill("incorrect");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("账号或密码有误");
});
test("人工建单、派工、模拟调查、反馈、验证和复盘", async ({ page }) => {
  await login(page);
  await page.getByRole("button", { name: "创建工单" }).click();
  await page.getByLabel("事件标题").fill("新故障");
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(page.getByRole("heading", { name: /新故障/ })).toBeVisible();
  for (const action of ["分派", "开始处理"]) {
    await page.getByRole("button", { name: action, exact: true }).click();
    await page.getByLabel("处理说明").fill(action + "记录");
    await page.getByRole("button", { name: "确认", exact: true }).click();
  }
  await page.getByRole("button", { name: "发起调查" }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(page.getByText("模拟日志")).toBeVisible();
  await page.getByRole("button", { name: "填写反馈" }).click();
  await page.getByLabel("核查结果").fill("已复核样本");
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(page.getByText("已复核样本")).toBeVisible();
  await page.getByRole("link", { name: /工单 #\d+/ }).click();
  for (const [action, field] of [
    ["验证恢复", "恢复验证依据"],
    ["关闭并复盘", "复盘记录"],
  ]) {
    await page.getByRole("button", { name: action, exact: true }).click();
    await page.getByLabel(field).fill(action + "记录");
    await page.getByRole("button", { name: "确认", exact: true }).click();
  }
  await expect(page.getByText("已关闭", { exact: true })).toBeVisible();
});
