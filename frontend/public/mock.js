// All records are synthetic and scoped to the current tab.
const now = () => new Date().toISOString();
const users = [
  { id: 1, username: "admin", role: "ADMIN" },
  { id: 2, username: "operator", role: "OPERATOR" },
];
const services = [
  {
    id: 1,
    name: "checkout",
    description: "订单结算服务",
    resource: "HTTP · /checkout",
  },
  {
    id: 2,
    name: "payment-worker",
    description: "支付任务消费者",
    resource: "Worker · payment",
  },
  {
    id: 3,
    name: "edge-gateway",
    description: "边缘入口",
    resource: "HTTP · /gateway",
  },
];
const alerts = [
  {
    id: 1,
    serviceId: 1,
    source: "demo",
    externalId: "checkout-503",
    severity: "CRITICAL",
    title: "结算接口持续返回 503",
    occurredAt: now(),
  },
  {
    id: 2,
    serviceId: 2,
    source: "demo",
    externalId: "payment-lag",
    severity: "WARNING",
    title: "支付队列消费延迟",
    occurredAt: now(),
  },
];
const tickets = [
  {
    id: 1,
    serviceId: 1,
    alertId: 1,
    title: alerts[0].title,
    status: "OPEN",
    version: 0,
    createdAt: now(),
    timeline: [
      {
        action: "CREATE",
        actor: "admin",
        createdAt: now(),
        note: "根据模拟告警建单",
      },
    ],
  },
];
const sampleTime = (minutes) =>
  new Date(Date.now() - minutes * 60000).toISOString();
const trace = (action, actor, note, minutes) => ({
  action,
  actor,
  note,
  createdAt: sampleTime(minutes),
});
tickets[0].createdAt = sampleTime(42);
tickets[0].status = "PROCESSING";
tickets[0].assigneeId = 2;
tickets[0].version = 3;
tickets[0].timeline = [
  trace("CREATE", "admin", "根据结算接口持续 503 的告警创建工单。", 42),
  trace("ASSIGN", "admin", "已分派给当班运维，优先核查结算依赖。", 38),
  trace("START", "operator", "在示例环境复现 503，开始调查 checkout。", 31),
  trace("NOTE", "operator", "已关联只读调查结果，等待人工确认连接池配置。", 19),
];
for (const [id, serviceId, title, status, minutes] of [
  [2, 2, "支付任务队列积压", "ASSIGNED", 75],
  [3, 3, "网关请求延迟升高", "VERIFIED", 130],
  [4, 1, "结算服务探测超时", "CLOSED", 194],
]) {
  tickets.push({
    id,
    serviceId,
    title,
    status,
    version: 1,
    assigneeId: 2,
    createdAt: sampleTime(minutes),
    timeline: [
      trace("CREATE", "operator", "从服务巡检记录创建示例工单。", minutes),
    ],
  });
}
alerts.push({
  id: 3,
  serviceId: 3,
  source: "gateway-probe",
  externalId: "edge-rt",
  severity: "INFO",
  title: "入口探测延迟超过阈值",
  occurredAt: sampleTime(56),
});
const tasks = [
  {
    id: 1,
    ticketId: 1,
    status: "SUCCEEDED",
    mode: "模拟数据",
    createdAt: sampleTime(27),
    startedAt: sampleTime(26),
    finishedAt: sampleTime(25),
    summary: "连接池无可用许可，结算请求返回 503。",
    feedback: "已核对模拟请求日志与错误计数，下一步检查池容量。",
    result: {
      sampledAt: sampleTime(26),
      snapshotId: "00000000000000000000000000000001",
      evidence: [
        {
          source: "checkout:/metrics",
          detail: "模拟采样窗口共收到 18 次请求，其中 6 次返回 5xx。",
        },
        {
          source: "checkout:/logs",
          detail: "request=demo-checkout-031 status=503 event=pool_exhausted",
        },
      ],
      possibleCause: "连接池无可用许可，结算请求在获取连接时失败。",
      uncertainty: "现有日志无法区分连接长期占用与连接池容量设置过小。",
      recommendation:
        "人工比对连接占用时间和池容量，恢复后重新请求 /checkout 验证。",
    },
  },
];
const passwords = new Map([
  ["admin", "demo"],
  ["operator", "demo"],
]);
let me;
export const demo = true;
export async function request(path, body) {
  if (path === "/notifications") return { items: [], nextCursor: null };
  if (path === "/budget") return { mode: "模拟数据", dailyBudgetUsd: "", reservationUsd: "", reservations: [] };
  if (path === "/sources") return { sources: [{ id: "checkout-demo", name: "模拟 checkout" }] };
  if (path === "/account/profile") { me.email = body.email; return { ok: true }; }

  await new Promise((resolve) => setTimeout(resolve, 50));
  if (path === "/login") {
    if (passwords.get(body.username) !== body.password)
      throw Error("账号或密码有误");
    return (me = users.find((u) => u.username === body.username));
  }
  if (!me) throw Object.assign(Error("请先登录"), { status: 401 });
  if (path === "/me") return me;
  if (path === "/services/1/metrics")
    return { service: "checkout", observedAt: now(), requests: 1, errors: 1 };
  if (path === "/services/1/logs")
    return {
      service: "checkout",
      entries: [
        { at: now(), event: "模拟连接池耗尽", status: 503, id: "demo-1" },
      ],
    };
  if (path === "/logout") {
    me = null;
    return;
  }
  const [pathname, query] = path.slice(1).split("?");
  const [group, id, operation] = pathname.split("/");
  const serviceName = (row) =>
    services.find((s) => s.id === row.serviceId)?.name;
  if (group === "overview") {
    const count = (rows, key, states) =>
      Object.fromEntries(
        states.map((s) => [s, rows.filter((r) => r[key] === s).length]),
      );
    return {
      openTickets: tickets.filter((t) => t.status !== "CLOSED").length,
      criticalAlerts: alerts.filter((a) => a.severity === "CRITICAL").length,
      activeTasks: tasks.filter((t) =>
        ["PENDING", "RUNNING"].includes(t.status),
      ).length,
      services: services.length,
      taskCounts: count(tasks, "status", [
        "PENDING",
        "RUNNING",
        "SUCCEEDED",
        "FAILED",
      ]),
      recentTickets: tickets
        .slice(0, 5)
        .map((t) => ({ ...t, serviceName: serviceName(t) })),
      recentAlerts: alerts
        .slice(0, 5)
        .map((a) => ({ ...a, serviceName: serviceName(a) })),
    };
  }
  const collection = { services, users, alerts, tickets, tasks }[group];
  if (!collection) throw Error("入口不存在");
  if (id === "page") {
    const params = new URLSearchParams(query),
      page = Number(params.get("page") || 1),
      size = Number(params.get("size") || 20);
    const filtered = collection.filter(
      (row) =>
        ["status", "severity", "serviceId", "assigneeId", "ticketId"].every(
          (key) => {
            if (!params.has(key)) return true;
            const value =
              group === "tasks" && key === "serviceId"
                ? tickets.find((t) => t.id === row.ticketId)?.serviceId
                : row[key];
            return String(value) === params.get(key);
          },
        ) &&
        (!params.get("q") ||
          (row.title + " " + row.id)
            .toLowerCase()
            .includes(params.get("q").toLowerCase())),
    );
    const items = filtered.slice((page - 1) * size, page * size).map((row) => {
      const ticket =
        group === "tasks" ? tickets.find((t) => t.id === row.ticketId) : row;
      if (group === "tasks")
        return {
          id: row.id,
          ticketId: row.ticketId,
          status: row.status,
          mode: row.mode,
          summary: row.summary,
          createdAt: row.createdAt,
          serviceName: serviceName(ticket),
          ticketTitle: ticket.title,
        };
      return {
        ...row,
        serviceName: serviceName(row),
        assigneeName: users.find((u) => u.id === row.assigneeId)?.username,
        ticketId: tickets.find((t) => t.alertId === row.id)?.id,
      };
    });
    return { page, size, items, total: filtered.length };
  }
  const item = id && collection.find((x) => x.id === Number(id));
  if (id && !item) throw Error("记录不存在");
  if (!body) return structuredClone(item || collection);
  if (["users", "services"].includes(group) && me.role !== "ADMIN")
    throw Error("需要管理员权限");
  if (operation === "actions") {
    const step = {
      ASSIGN: [item.status === "ASSIGNED" ? "ASSIGNED" : "OPEN", "ASSIGNED"],
      START: ["ASSIGNED", "PROCESSING"],
      VERIFY: ["PROCESSING", "VERIFIED"],
      CLOSE: ["VERIFIED", "CLOSED"],
    }[body.action];
    if (item.version !== body.version || (step && step[0] !== item.status))
      throw Error("状态已变化，请刷新");
    if (step) item.status = step[1];
    if (body.action === "ASSIGN") item.assigneeId = Number(body.assigneeId);
    if (body.action === "CLOSE") item.review = body.note;
    item.version++;
    item.timeline.push({
      action: body.action,
      actor: me.username,
      note: body.note,
      createdAt: now(),
    });
    return item;
  }
  if (operation === "feedback") {
    item.feedback = body.feedback;
    return item;
  }
  let record = { ...body, id: collection.length + 1, createdAt: now() };
  if (group === "tickets") {
    const existing = tickets.find(
      (t) => body.alertId && t.alertId === body.alertId,
    );
    if (existing) return existing;
    Object.assign(record, {
      status: "OPEN",
      version: 0,
      timeline: [
        {
          action: "CREATE",
          note: "创建工单",
          actor: me.username,
          createdAt: now(),
        },
      ],
    });
  }
  if (group === "alerts") {
    const existing = alerts.find(
      (a) => a.source === body.source && a.externalId === body.externalId,
    );
    if (existing) return existing;
  }
  if (group === "tasks")
    Object.assign(record, {
      status: "SUCCEEDED",
      mode: "模拟数据",
      result: {
        evidence: [{ source: "模拟日志", detail: "示例请求返回 503" }],
        possibleCause: "示例依赖连接超时",
        uncertainty: "仅用于界面演示",
        recommendation: "人工核查依赖可达性",
      },
    });
  if (group === "users") {
    if (passwords.has(body.username)) throw Error("账号已存在");
    passwords.set(body.username, body.password);
    delete record.password;
  }
  collection.unshift(record);
  return record;
}
