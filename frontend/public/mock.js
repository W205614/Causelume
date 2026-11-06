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
const tasks = [];
const passwords = new Map([
  ["admin", "demo"],
  ["operator", "demo"],
]);
let me;
export const demo = true;
export async function request(path, body) {
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
  const [group, id, operation] = path.slice(1).split("/");
  const collection = { services, users, alerts, tickets, tasks }[group];
  if (!collection) throw Error("入口不存在");
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
