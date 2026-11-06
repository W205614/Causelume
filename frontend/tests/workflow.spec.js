import { expect, test } from "@playwright/test";
const heading = (page, name) =>
  page.getByRole("heading", { name, exact: true });
const button = (page, name) => page.getByRole("button", { name, exact: true });
const me = { id: 1, username: "admin", role: "ADMIN" };
const service = {
  id: 4,
  name: "checkout",
  resource: "HTTP",
  description: "结算",
};
const ticket = {
  id: 7,
  title: "结算故障",
  serviceId: 4,
  version: 0,
  status: "OPEN",
  createdAt: "2026-11-06T10:00:00Z",
  timeline: [],
};
const alert = { id: 11, title: "超时告警", serviceId: 4, severity: "WARNING" };
const pending = { id: 5, ticketId: 7, status: "PENDING" };
async function serve(page, respond) {
  await page.route("**/api/**", async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname.slice(4);
    const payload = req.method() === "POST" ? req.postDataJSON() : null;
    const overrides = await respond?.(path, payload);
    const tables = {
      "/me": me,
      "/tickets": [ticket],
      "/tickets/7": ticket,
      "/alerts": [alert],
      "/services": [service],
      "/users": [me],
      "/tasks": [pending],
      "/audit": [],
    };
    const body = overrides?.body ?? tables[path];
    await route.fulfill({
      status: overrides?.status ?? (body === undefined ? 404 : 200),
      json: body ?? { message: "missing " + path },
    });
  });
}
test("工单列表手动建单、告警关联建单，列表不拉取告警", async ({ page }) => {
  const writes = [];
  let alertReads = 0;
  await serve(page, (path, body) => {
    if (path === "/alerts") alertReads++;
    if (path === "/tickets" && body) {
      writes.push(body);
      return { body: ticket };
    }
  });
  await page.goto("/#tickets");
  await page.getByRole("button", { name: "创建工单" }).click();
  await expect(page.getByLabel("事件标题")).toBeVisible();
  await page.getByLabel("事件标题").fill("手工创建");
  await button(page, "确认").click();
  await expect(page).toHaveURL(/#tickets\/7$/);
  expect(alertReads).toBe(0);
  expect(writes[0]).not.toHaveProperty("alertId");
  await page
    .getByRole("navigation")
    .getByRole("link", { name: "告警中心" })
    .click();
  await button(page, "创建工单").click();
  await expect(page.getByLabel("事件标题")).toHaveValue("超时告警");
  await button(page, "确认").click();
  await expect(page).toHaveURL(/#tickets\/7$/);
  expect(writes[1].alertId).toBe(11);
});
for (const interrupt of ["取消", "离开页面"]) {
  test("提交后" + interrupt + "，迟到响应不打断新弹窗", async ({ page }) => {
    let start, done;
    const started = new Promise((r) => (start = r)),
      release = new Promise((r) => (done = r));
    await serve(page, async (path, body) => {
      if (path === "/tickets" && body) {
        start();
        await release;
        return { body: ticket };
      }
    });
    await page.goto("/#services");
    await heading(page, "服务与资源").waitFor();
    await page
      .getByRole("navigation")
      .getByRole("link", { name: "运维概览" })
      .click();
    await page.getByRole("button", { name: "创建工单" }).click();
    await page.getByLabel("事件标题").fill("旧工单");
    await button(page, "确认").click();
    await started;
    if (interrupt === "取消") await button(page, "取消").click();
    else {
      await page.goBack();
      await heading(page, "服务与资源").waitFor();
    }
    if (interrupt === "取消")
      await page.getByRole("button", { name: "创建工单" }).click();
    else await page.getByRole("button", { name: "添加服务" }).click();
    done();
    await expect(page.getByRole("status")).toContainText("已提交");
    await expect(page.getByRole("dialog")).toBeVisible();
    await expect(page).toHaveURL(
      interrupt === "取消" ? /#overview$/ : /#services$/,
    );
  });
}
test("并发冲突显示最新工单内容，保留输入并重新确认", async ({ page }) => {
  let writes = [],
    conflict = false;
  await serve(page, (path, body) => {
    if (path === "/tickets/7/actions") {
      writes.push(body);
      if (!conflict) {
        conflict = true;
        return { status: 409, body: { message: "版本已变化" } };
      }
      return { body: ticket };
    }
    if (path === "/tickets/7" && conflict)
      return {
        body: {
          ...ticket,
          version: 1,
          status: "ASSIGNED",
          assigneeId: 1,
          timeline: [
            {
              actor: "admin",
              action: "ASSIGN",
              note: "另一位同事已派工",
              createdAt: ticket.createdAt,
            },
          ],
        },
      };
  });
  await page.goto("/#tickets/7");
  await button(page, "添加记录").click();
  await page.getByLabel("处理说明").fill("保留我的现场记录");
  await button(page, "确认").click();
  await expect(page.getByRole("dialog")).toContainText("另一位同事已派工");
  await expect(page.getByLabel("处理说明")).toHaveValue("保留我的现场记录");
  expect(writes).toHaveLength(1);
  await button(page, "重新确认").click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  expect(writes.map((w) => w.version)).toEqual([0, 1]);
  expect(writes[1].note).toBe("保留我的现场记录");
});
test("概览计入待执行调查", async ({ page }) => {
  await serve(page);
  await page.goto("/#overview");
  await expect(page.getByText(/等待调查\s*1 项/)).toBeVisible();
});
