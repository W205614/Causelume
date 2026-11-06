import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
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

test("严格生产 CSP 下概览按真实队列数量显示比例", async ({ page }) => {
  const config = readFileSync(new URL("../../deployment/caddy/Caddyfile", import.meta.url), "utf8");
  const policy = config.match(/Content-Security-Policy "([^"]+)"/)[1];
  await page.addInitScript(() => {
    window.cspViolations = [];
    document.addEventListener("securitypolicyviolation", e => window.cspViolations.push(e.effectiveDirective));
  });
  await page.route("**/index.html", async route => {
    const response = await route.fetch();
    await route.fulfill({ response, headers: { ...response.headers(), "Content-Security-Policy": policy } });
  });
  await serve(page, path => path === "/overview" ? { body: {
    openTickets: 0, criticalAlerts: 0, activeTasks: 4, services: 1, recentTickets: [], recentAlerts: [],
    taskCounts: { PENDING: 3, RUNNING: 1, FAILED: 0, SUCCEEDED: 0 }
  } } : undefined);
  await page.goto("/index.html#overview");
  const bars = page.locator(".track i");
  await expect(bars).toHaveCount(2);
  const [first, second] = await bars.evaluateAll(nodes => nodes.map(n => n.getBoundingClientRect().width));
  expect(first / second).toBeCloseTo(3, 1);
  expect(await page.evaluate(() => window.cspViolations)).toEqual([]);
});

test("投递异常翻页保持管理范围，核对预算可填写六位小数", async ({ page }) => {
  let amount;
  await serve(page, (path, body, params) => {
    if (path === "/notifications") return { body: { items: [{
      id: 9, recipientId: 8, eventType: "ALERT", ticketId: 7, mailStatus: "UNKNOWN", attempts: 1,
      message: params.has("afterId") && params.get("scope") === "delivery" ? "较早的异常" : "首条异常"
    }], nextCursor: params.has("afterId") ? null : 9 } };
    if (path === "/budget") return { body: { mode: "offline", reservations: [{ taskId: 5, state: "UNKNOWN", reservedUsd: 1 }] } };
    if (path === "/budget/5/reconcile") { amount = body.actualCostUsd; return { body: { ok: true } }; }
  });
  await page.goto("/#notifications?scope=delivery");
  await expect(button(page, "标记已读")).toHaveCount(0);
  await page.getByRole("link", { name: "更早通知" }).click();
  await expect(page.getByText("较早的异常")).toBeVisible();
  await page.getByRole("link", { name: "模型预算" }).click();
  await button(page, "核对费用").click();
  await page.getByLabel("供应商实际费用（美元）").fill("0.123456");
  await page.getByLabel("核对依据").fill("模拟对账单");
  await button(page, "确认").click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(amount).toBe(0.123456);
});

test("服务与账号目录翻页，服务编辑冲突保留草稿并复核", async ({ page }) => {
  const writes = [];
  const current = { ...service, id: 105, name: "second-service", environment: "test", ownerId: 201, active: true, version: 1 };
  await serve(page, (path, body, params) => {
    if (path === "/services") return params.has("afterId")
      ? { body: [{ ...current, resource: writes.length ? "最新资源" : "原始资源", version: writes.length ? 2 : 1 }] }
      : { body: [service], headers: { "X-Next-Cursor": "4" } };
    if (path === "/users") return params.has("afterId")
      ? { body: [{ id: 201, username: "第二页运维", active: true }] }
      : { body: [me], headers: { "X-Next-Cursor": "1" } };
    if (path === "/sources") return { body: { sources: [] } };
    if (path === "/services/105") {
      writes.push(body);
      return writes.length === 1 ? { status: 409, body: { message: "版本冲突" } } : { body: current };
    }
  });
  await page.goto("/#services");
  await page.locator("article").filter({ hasText: "second-service" }).getByRole("button", { name: "编辑" }).click();
  await expect(page.getByLabel("默认负责人")).toHaveValue("201");
  await page.getByLabel("描述").fill("我的草稿");
  await button(page, "确认").click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText("最新资源");
  await expect(page.getByLabel("描述")).toHaveValue("我的草稿");
  await expect(page.getByLabel("资源标识")).toHaveValue("最新资源");
  expect(writes).toHaveLength(1);
  await button(page, "重新确认").click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(writes[1]).toMatchObject({ version: 2, description: "我的草稿", resource: "最新资源" });
});

test("绑定期间会话失效清除密钥，登录时可补验证码", async ({ page }) => {
  let first = true;
  await serve(page, (path, body) => {
    if (path === "/me") return { body: { ...me, enrollmentRequired: true } };
    if (path === "/account/mfa/enroll") return { body: { secret: "ONLYFORFIRSTUSER" } };
    if (path === "/account/mfa/confirm") return { status: 401, body: { message: "请重新登录" } };
    if (path === "/login") {
      if (first) { first = false; return { status: 401, body: { message: "请输入验证码", code: "MFA_REQUIRED" } }; }
      return { body: { ...me, id: 12, username: body.username, mfaEnabled: true } };
    }
  });
  await page.goto("/#account");
  await button(page, "绑定验证器").click();
  await page.getByLabel("当前密码").fill("test-fixture");
  await button(page, "确认").click();
  await expect(page.locator("code")).toHaveText("ONLYFORFIRSTUSER");
  await button(page, "输入验证码完成绑定").click();
  await page.getByLabel("六位验证码").fill("123456");
  await button(page, "确认").click();
  await expect(heading(page, "登录运维控制台")).toBeVisible();
  await expect(page.locator("#dialog input")).toHaveCount(0);
  await page.getByLabel("账号", { exact: true }).fill("second");
  await page.getByLabel("密码", { exact: true }).fill("second-test-password");
  await button(page, "登录").click();
  await expect(page.getByRole("alert")).toHaveText("请输入验证码");
  await expect(page.getByLabel("密码", { exact: true })).toHaveValue("second-test-password");
  await page.getByLabel("验证码或恢复码").fill("654321");
  await button(page, "登录").click();
  await expect(heading(page, "账户安全")).toBeVisible();
  await expect(page.getByText("ONLYFORFIRSTUSER")).toHaveCount(0);
});

test("关闭工单仅负责人显示导出与重开，禁止调整优先级", async ({ page }) => {
  let assigneeId = 20;
  await serve(page, path => {
    if (path === "/me") return { body: { id: 12, username: "oncall", role: "OPERATOR" } };
    if (path === "/tickets/7") return { body: { ...ticket, assigneeId, status: "CLOSED",
      improvements: [{ id: 80, title: "我负责的事项", ownerId: 12, status: "OPEN" }]
    } };
  });
  await page.goto("/#tickets/7");
  await expect(page.getByRole("heading", { name: new RegExp(ticket.title) })).toBeVisible();
  await expect(page.getByRole("link", { name: "导出复盘" })).toHaveCount(0);
  await expect(button(page, "重新打开")).toHaveCount(0);
  await expect(button(page, "标记完成")).toBeVisible();
  assigneeId = 12;
  await page.reload();
  await expect(page.getByRole("link", { name: "导出复盘" })).toBeVisible();
  await expect(button(page, "重新打开")).toBeVisible();
  await expect(button(page, "优先级")).toHaveCount(0);
});
async function serve(page, respond) {
  await page.route("**/api/**", async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname.slice(4);
    const payload = req.method() === "POST" ? req.postDataJSON() : null;
    const overrides = await respond?.(path, payload, url.searchParams);
    const paged = (items) => ({
      items,
      total: items.length,
      page: 1,
      size: 20,
    });
    const tables = {
      "/me": me,
      "/tickets": [ticket],
      "/tickets/7": ticket,
      "/alerts": [alert],
      "/services": [service],
      "/users": [me],
      "/tasks": [pending],
      "/audit": [],
      "/overview": {
        openTickets: 1,
        criticalAlerts: 0,
        activeTasks: 1,
        services: 1,
        taskCounts: { PENDING: 1, RUNNING: 0, SUCCEEDED: 0, FAILED: 0 },
        recentTickets: [ticket],
        recentAlerts: [alert],
      },
      "/tickets/page": paged([ticket]),
      "/alerts/page": paged([alert]),
      "/tasks/page": paged([pending]),
      "/tasks/5": pending,
    };
    const body = overrides?.body ?? tables[path];
    await route.fulfill({
      status: overrides?.status ?? (body === undefined ? 404 : 200),
      headers: overrides?.headers,
      json: body ?? { message: "missing " + path },
    });
  });
}
test("工单列表手动建单、告警关联建单，列表不拉取告警", async ({ page }) => {
  const writes = [];
  let alertReads = 0;
  await serve(page, (path, body) => {
    if (path.startsWith("/alerts")) alertReads++;
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
test("工单分页和筛选通过查询参数交给服务端", async ({ page }) => {
  const reads = [];
  await serve(page, (path, body, params) => {
    if (path !== "/tickets/page") return;
    reads.push(new URLSearchParams(params));
    const p = Number(params.get("page") || 1);
    const total = params.has("status") ? 1 : 23;
    return {
      body: {
        page: p,
        size: 20,
        total,
        items: [{ ...ticket, title: "服务端第 " + p + " 页" }],
      },
    };
  });
  await page.goto("/#tickets");
  await page.getByRole("link", { name: "下一页" }).click();
  await expect(page.getByText("服务端第 2 页")).toBeVisible();
  await page.getByLabel("工单状态").selectOption("ASSIGNED");
  await page.getByLabel("处理人").selectOption("1");
  await page.getByLabel("搜索工单").fill("结算");
  await button(page, "筛选").click();
  await expect(page.getByText("共 1 条")).toBeVisible();
  const last = reads.at(-1);
  expect([
    last.get("status"),
    last.get("assigneeId"),
    last.get("q"),
    last.get("page"),
  ]).toEqual(["ASSIGNED", "1", "结算", null]);
});
test("告警筛选级别和服务，空页可清除", async ({ page }) => {
  await serve(page, (path, body, params) => {
    if (
      path === "/alerts/page" &&
      params.get("severity") === "CRITICAL" &&
      params.get("serviceId") === "4"
    )
      return { body: { page: 1, size: 20, total: 0, items: [] } };
  });
  await page.goto("/#alerts");
  await page.getByLabel("严重级别").selectOption("CRITICAL");
  await page.getByLabel("关联服务").selectOption("4");
  await button(page, "筛选").click();
  await expect(page.getByText("暂无记录")).toBeVisible();
  await page.getByRole("link", { name: "清除筛选" }).click();
  await expect(page.getByText("超时告警")).toBeVisible();
});
test("任务列表使用摘要，详情自动刷新，离开页面后停止", async ({ page }) => {
  let detailReads = 0,
    listReads = 0;
  await page.clock.install();
  await serve(page, (path) => {
    if (path === "/tasks/page") {
      listReads++;
      return {
        body: {
          page: 1,
          size: 20,
          total: 1,
          items: [{ ...pending, summary: "待采集结算样本" }],
        },
      };
    }
    if (path === "/tasks/5") {
      detailReads++;
      return {
        body: { ...pending, status: detailReads === 1 ? "PENDING" : "RUNNING" },
      };
    }
  });
  await page.goto("/#tasks");
  await expect(page.getByText("待采集结算样本")).toBeVisible();
  expect(detailReads).toBe(0);
  await page.getByRole("link", { name: "#5" }).click();
  await expect(heading(page, "调查 #5")).toBeVisible();
  await expect(page.getByText("等待调查", { exact: true })).toBeVisible();
  await page.clock.fastForward(5100);
  await expect(page.getByText("调查中", { exact: true })).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("link", { name: "服务与资源" })
    .click();
  await heading(page, "服务与资源").waitFor();
  const last = detailReads;
  await page.clock.fastForward(16000);
  expect(detailReads).toBe(last);
  expect(listReads).toBe(1);
});
