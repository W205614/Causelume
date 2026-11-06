export const esc = (v) =>
  String(v ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const paths = {
  brand: "M3 15h4l3-10 4 15 3-11 2 6h2",
  overview: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  services: "M3 4h18v6H3z M3 14h18v6H3z M6 7h1 M6 17h1",
  alerts: "M12 3 2 21h20z M12 9v5 M12 17h.01",
  tickets: "M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h4",
  tasks: "M4 12h5l3-8 3 16 3-8h3",
  users:
    "M5 20v-2a5 5 0 0 1 10 0v2 M14 6a4 4 0 1 1-8 0 4 4 0 0 1 8 0z M17 4a4 4 0 0 1 0 8 M18 15a4 4 0 0 1 3 4",
};
export const icon = (n) =>
  '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="' +
  paths[n] +
  '"/></svg>';
export const brand =
  '<div class="brand"><span class="mark">' +
  icon("brand") +
  "</span>Causelume</div>";
export const pages = {
  overview: "运维概览",
  services: "服务与资源",
  alerts: "告警中心",
  tickets: "事件与工单",
  tasks: "Agent 调查",
  users: "用户管理",
};
const labels = {
  OPEN: "待分派",
  ASSIGNED: "已分派",
  PROCESSING: "处理中",
  VERIFIED: "已验证",
  CLOSED: "已关闭",
  PENDING: "等待调查",
  RUNNING: "调查中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CRITICAL: "严重",
  WARNING: "警告",
  INFO: "提示",
  ASSIGN: "分派",
  START: "开始处理",
  VERIFY: "验证恢复",
  CLOSE: "关闭并复盘",
  NOTE: "添加记录",
  CREATE: "创建",
  ADMIN: "管理员",
  OPERATOR: "运维人员",
  offline: "离线规则 · 无模型调用",
  holmes: "HolmesGPT 模型调查",
  QUEUED: "排队中",
};
export const label = (v) => labels[v] ?? v;
const pill = (v) =>
  '<span class="badge ' + esc(v) + '">' + esc(label(v)) + "</span>";
const time = (v) =>
  v ? esc(new Date(v).toLocaleString("zh-CN", { hour12: false })) : "—";
const button = (op, name, primary = false) =>
  '<button data-op="' +
  op +
  '" class="' +
  (primary ? "primary" : "") +
  '">' +
  name +
  "</button>";
const header = (title, actions = "") =>
  '<div class="head"><h1>' +
  esc(title) +
  '</h1><div class="actions">' +
  actions +
  "</div></div>";
const panel = (title, body, link = "") =>
  '<section class="panel"><div class="panel-head"><h2>' +
  esc(title) +
  "</h2>" +
  link +
  "</div>" +
  body +
  "</section>";
function table(headers, rows) {
  if (!rows.length) return '<div class="empty">暂无记录</div>';
  return (
    '<div class="scroll"><table><thead><tr>' +
    headers.map((h) => "<th>" + h + "</th>").join("") +
    "</tr></thead><tbody>" +
    rows
      .map(
        (cells) =>
          "<tr>" +
          cells
            .map(
              (c, i) =>
                '<td class="' + (i === 1 ? "wrap" : "") + '">' + c + "</td>",
            )
            .join("") +
          "</tr>",
      )
      .join("") +
    "</tbody></table></div>"
  );
}
export function ticketTable(tickets, services) {
  return table(
    ["编号", "事件标题", "服务", "状态", "创建时间"],
    tickets.map((t) => [
      '<a href="#tickets/' + t.id + '">#' + t.id + "</a>",
      '<a href="#tickets/' + t.id + '">' + esc(t.title) + "</a>",
      esc(services.find((s) => s.id === t.serviceId)?.name),
      pill(t.status),
      time(t.createdAt),
    ]),
  );
}
const navLink = (page, title) => '<a href="#' + page + '">' + title + " →</a>";
export function overview({ tickets, services, alerts, tasks }) {
  const active = tickets.filter((t) => t.status !== "CLOSED").length;
  const stats = [
    ["tickets", active, "未关闭工单"],
    ["alerts", alerts.length, "告警记录"],
    ["services", services.length, "监控服务"],
  ];
  return (
    header("运维概览", button("newTicket", "＋ 创建工单", true)) +
    '<div class="stats">' +
    stats
      .map(
        ([key, num, text]) =>
          '<div class="stat"><strong>' +
          num +
          "</strong>" +
          icon(key) +
          "<span>" +
          text +
          "</span></div>",
      )
      .join("") +
    "</div>" +
    panel(
      "近期工单",
      ticketTable(tickets.slice(0, 5), services),
      navLink("tickets", "查看全部"),
    ) +
    '<div class="grid">' +
    panel(
      "告警分布",
      '<div class="pad stack">' +
        ["CRITICAL", "WARNING", "INFO"]
          .map(
            (s) =>
              "<div>" +
              pill(s) +
              "　" +
              alerts.filter((a) => a.severity === s).length +
              " 条</div>",
          )
          .join("") +
        "</div>",
      navLink("alerts", "告警中心"),
    ) +
    panel(
      "调查任务",
      '<div class="pad stack">' +
        ["RUNNING", "SUCCEEDED", "FAILED"]
          .map(
            (s) =>
              "<div>" +
              pill(s) +
              "　" +
              tasks.filter((t) => t.status === s).length +
              " 项</div>",
          )
          .join("") +
        "</div>",
      navLink("tasks", "查看调查"),
    ) +
    "</div>"
  );
}
export const ticketsPage = ({ tickets, services }) =>
  header(pages.tickets, button("newTicket", "＋ 创建工单", true)) +
  '<label class="filter"><input id="filter" placeholder="搜索工单标题或编号" aria-label="搜索工单"></label>' +
  panel(
    "工单列表",
    '<div id="matches">' + ticketTable(tickets, services) + "</div>",
  );
export function servicesPage({ services, admin }) {
  return (
    header(
      pages.services,
      admin ? button("newService", "＋ 添加服务", true) : "",
    ) +
    (services.length
      ? '<div class="grid">' +
        services
          .map(
            (s) =>
              '<article class="item">' +
              icon("services") +
              '<p class="mono"><strong>' +
              esc(s.name) +
              "</strong></p><p>" +
              esc(s.description) +
              '</p><p class="muted">' +
              esc(s.resource) +
              "</p>" +
              (s.name === "checkout"
                ? navLink("services/" + s.id, "查看日志与指标")
                : "") +
              "</article>",
          )
          .join("") +
        "</div>"
      : '<p class="empty">暂无服务</p>')
  );
}
export function observations(service, metrics, logs) {
  return (
    navLink("services", "返回服务列表") +
    header(service.name, button("refresh", "刷新读数")) +
    panel(
      "请求指标",
      '<div class="pad"><p>采样时间　' +
        time(metrics.observedAt) +
        "</p><p>最近请求　" +
        esc(metrics.requests) +
        "　　错误请求　" +
        esc(metrics.errors) +
        "</p></div>",
    ) +
    panel(
      "请求日志",
      table(
        ["时间", "事件", "HTTP 状态", "请求 ID"],
        logs.entries.map((row) => [
          time(row.at),
          esc(row.event),
          esc(row.status),
          esc(row.id),
        ]),
      ),
    )
  );
}
export const alertsPage = ({ alerts, services, tickets }) =>
  header(pages.alerts, button("newAlert", "＋ 录入告警", true)) +
  panel(
    "告警记录",
    table(
      ["级别", "告警标题", "服务", "来源", "发生时间", "关联工单"],
      alerts.map((a) => {
        const t = tickets.find((t) => t.alertId === a.id);
        return [
          pill(a.severity),
          esc(a.title),
          esc(services.find((s) => s.id === a.serviceId)?.name),
          esc(a.source),
          time(a.occurredAt),
          t
            ? navLink("tickets/" + t.id, "#" + t.id)
            : button("fromAlert:" + a.id, "创建工单"),
        ];
      }),
    ),
  );
export const usersPage = ({ users, admin, audit }) =>
  admin
    ? header(pages.users, button("newUser", "＋ 添加用户", true)) +
      panel(
        "成员名单",
        table(
          ["编号", "账号", "角色"],
          users.map((u) => [u.id, esc(u.username), esc(label(u.role))]),
        ),
      ) +
      (audit
        ? panel(
            "操作审计",
            table(
              ["操作者", "操作", "对象编号", "发生时间"],
              audit.map((entry) => [
                esc(entry.actor),
                esc(entry.action),
                esc(entry.targetId),
                time(entry.createdAt),
              ]),
            ),
          )
        : "")
    : header(pages.users) +
      '<div class="retry"><strong>权限不足</strong><p>此页面仅对管理员开放。</p></div>';
export function tasksPage({ tasks }) {
  return (
    header(pages.tasks) +
    panel(
      "调查任务",
      table(
        ["编号", "关联工单", "状态", "模式", "创建时间"],
        tasks.map((t) => [
          navLink("tasks/" + t.id, "#" + t.id),
          navLink("tickets/" + t.ticketId, "工单 #" + t.ticketId),
          pill(t.status),
          esc(label(t.mode)),
          time(t.createdAt),
        ]),
      ),
    )
  );
}
export function taskDetail(t, { tickets, admin, actorId }) {
  const r = t.result;
  let body =
    '<div class="pad"><p>' +
    pill(t.status) +
    "　" +
    esc(label(t.mode)) +
    "</p><p>关联 " +
    navLink("tickets/" + t.ticketId, "工单 #" + t.ticketId) +
    "</p>";
  if (t.error) body += '<p class="error">' + esc(t.error) + "</p>";
  if (r)
    body +=
      "<h3>证据来源</h3>" +
      (r.evidence || [])
        .map(
          (e) =>
            "<p><strong>" +
            esc(e.source) +
            "</strong> · " +
            esc(e.detail) +
            "</p>",
        )
        .join("") +
      "<h3>可能原因</h3><p>" +
      esc(r.possibleCause) +
      "</p><h3>不确定性</h3><p>" +
      esc(r.uncertainty) +
      "</p><h3>处理建议（待人工确认）</h3><p>" +
      esc(r.recommendation) +
      "</p>";
  if (t.feedback) body += "<h3>人工反馈</h3><p>" + esc(t.feedback) + "</p>";
  const owner = tickets.find((ticket) => ticket.id === t.ticketId)?.assigneeId;
  if (
    !t.feedback &&
    ["SUCCEEDED", "FAILED"].includes(t.status) &&
    (admin || actorId === owner)
  )
    body += button("feedback", "填写反馈");
  return (
    navLink("tasks", "返回任务列表") +
    header("调查 #" + t.id, button("refresh", "刷新状态")) +
    panel("调查结果", body + "</div>")
  );
}
export function detail(t, { users, services, tasks, admin, actorId }) {
  const next = {
    OPEN: "ASSIGN",
    ASSIGNED: "START",
    PROCESSING: "VERIFY",
    VERIFIED: "CLOSE",
  }[t.status];
  const canAdvance = admin || (next !== "ASSIGN" && actorId === t.assigneeId);
  let actions =
    next && canAdvance ? button("action:" + next, label(next), true) : "";
  if (admin && t.status === "ASSIGNED")
    actions += button("action:ASSIGN", "改派");
  if (next) actions += button("action:NOTE", "添加记录");
  const related = tasks.filter((task) => task.ticketId === t.id);
  if (
    ["ASSIGNED", "PROCESSING"].includes(t.status) &&
    canAdvance &&
    !related.some((task) =>
      ["QUEUED", "PENDING", "RUNNING"].includes(task.status),
    )
  )
    actions += button("investigate", "发起调查");
  const assignee = users.find((u) => u.id === t.assigneeId);
  const about =
    '<div class="pad"><p>' +
    pill(t.status) +
    "　" +
    esc(services.find((s) => s.id === t.serviceId)?.name) +
    "</p><p>处理人　" +
    esc(assignee?.username || "待分派") +
    "</p><p>创建时间　" +
    time(t.createdAt) +
    "</p>" +
    (t.review ? "<p>复盘　" + esc(t.review) + "</p>" : "") +
    "</div>";
  const timeline =
    '<ol class="timeline">' +
    t.timeline
      .map(
        (e) =>
          "<li><strong>" +
          esc(label(e.action)) +
          "</strong>　" +
          esc(e.actor) +
          "<p>" +
          esc(e.note) +
          "</p><time>" +
          time(e.createdAt) +
          "</time></li>",
      )
      .join("") +
    "</ol>";
  return (
    navLink("tickets", "返回工单列表") +
    header("#" + t.id + "　" + t.title, actions) +
    '<div class="grid">' +
    panel("工单信息", about) +
    panel(
      "关联调查",
      '<div class="pad stack">' +
        (related
          .map(
            (task) =>
              "<div>" +
              navLink("tasks/" + task.id, "调查 #" + task.id) +
              "　" +
              pill(task.status) +
              "</div>",
          )
          .join("") || "暂无调查任务") +
        "</div>",
    ) +
    "</div>" +
    panel("处理时间线", '<div class="pad">' + timeline + "</div>")
  );
}
