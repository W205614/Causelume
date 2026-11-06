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
    '<div class="scroll" tabindex="0" aria-label="数据表格"><table><thead><tr>' +
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
function ticketTable(tickets) {
  return table(
    ["工单", "关联服务", "状态"],
    tickets.map((t) => [
      '<div class="record"><small>工单 #' +
        t.id +
        '</small><a href="#tickets/' +
        t.id +
        '">' +
        esc(t.title) +
        "</a></div>",
      '<span class="resource">' + esc(t.serviceName) + "</span>",
      pill(t.status),
    ]),
  );
}
const navLink = (page, title) =>
  '<a href="#' + esc(page) + '">' + esc(title) + " →</a>";
const metric = (num, title, href, key) =>
  '<a class="metric metric-' +
  key +
  '" href="#' +
  href +
  '"><span>' +
  icon(key) +
  esc(title) +
  "</span><strong>" +
  num +
  "</strong></a>";
export function overview({ summary: s }) {
  const counts = s.taskCounts;
  const states = ["PENDING", "RUNNING", "SUCCEEDED", "FAILED"];
  const total = states.reduce((sum, key) => sum + counts[key], 0);
  const distribution = total
    ? '<div class="distribution"><span>调查状态分布 · 共 ' +
      total +
      ' 项</span><div class="track" aria-hidden="true">' +
      states
        .filter((key) => counts[key] > 0)
        .map(
          (key) =>
            '<i class="' +
            key +
            '" style="flex-grow:' +
            Number(counts[key]) +
            '"></i>',
        )
        .join("") +
      "</div></div>"
    : '<p class="empty">尚无调查任务</p>';
  const taskStates = states
    .map(
      (key) =>
        '<a class="count-line" href="#tasks?status=' +
        key +
        '">' +
        pill(key) +
        "<strong>" +
        counts[key] +
        " 项</strong></a>",
    )
    .join("");
  const alarms = s.recentAlerts
    .map(
      (a) =>
        "<li>" +
        pill(a.severity) +
        "<div><strong>" +
        esc(a.title) +
        "</strong><small>" +
        esc(a.serviceName) +
        " · " +
        esc(a.source) +
        "</small></div><time>" +
        time(a.occurredAt) +
        "</time></li>",
    )
    .join("");
  return (
    header("运维概览", button("newTicket", "＋ 创建工单", true)) +
    '<div class="metrics">' +
    metric(s.openTickets, "未关闭工单", "tickets", "tickets") +
    metric(s.criticalAlerts, "严重告警", "alerts?severity=CRITICAL", "alerts") +
    metric(s.activeTasks, "待执行与运行中调查", "tasks", "tasks") +
    metric(s.services, "监控服务", "services", "services") +
    "</div>" +
    '<div class="overview-columns">' +
    panel(
      "近期工单",
      ticketTable(s.recentTickets),
      navLink("tickets", "工单列表"),
    ) +
    panel(
      "调查队列",
      distribution + '<div class="queue">' + taskStates + "</div>",
      navLink("tasks", "查看调查"),
    ) +
    "</div>" +
    panel(
      "近期告警",
      alarms
        ? '<ul class="alerts-feed">' + alarms + "</ul>"
        : '<p class="empty">暂无告警</p>',
      navLink("alerts", "告警中心"),
    )
  );
}
function selectFilter(key, title, choices, params) {
  const options = choices
    .map(
      ([value, text]) =>
        '<option value="' +
        esc(value) +
        '" ' +
        (String(value) === params.get(key) ? "selected" : "") +
        ">" +
        esc(text) +
        "</option>",
    )
    .join("");
  return (
    "<label>" +
    title +
    '<select name="' +
    key +
    '"><option value="">全部</option>' +
    options +
    "</select></label>"
  );
}
const enumOptions = (keys) => keys.map((k) => [k, label(k)]);
function filters(group, data) {
  const { services, users, params } = data;
  const fields = [];
  if (group === "tickets")
    fields.push(
      '<label>工单搜索<input name="q" aria-label="搜索工单" placeholder="标题或编号" value="' +
        esc(params.get("q")) +
        '"></label>',
    );
  if (services)
    fields.push(
      selectFilter(
        "serviceId",
        "关联服务",
        services.map((s) => [s.id, s.name]),
        params,
      ),
    );
  if (group === "tickets") {
    fields.push(
      selectFilter(
        "status",
        "工单状态",
        enumOptions(["OPEN", "ASSIGNED", "PROCESSING", "VERIFIED", "CLOSED"]),
        params,
      ),
    );
    fields.push(
      selectFilter(
        "assigneeId",
        "处理人",
        users.map((u) => [u.id, u.username]),
        params,
      ),
    );
  }
  if (group === "alerts")
    fields.push(
      selectFilter(
        "severity",
        "严重级别",
        enumOptions(["CRITICAL", "WARNING", "INFO"]),
        params,
      ),
    );
  if (group === "tasks")
    fields.push(
      selectFilter(
        "status",
        "任务状态",
        enumOptions(["PENDING", "RUNNING", "SUCCEEDED", "FAILED"]),
        params,
      ),
    );
  return (
    '<form class="filters" data-filter>' +
    fields.join("") +
    "<button>筛选</button>" +
    navLink(group, "清除筛选") +
    "</form>"
  );
}
function pagination(group, { page: p, params }) {
  const totalPages = Math.max(1, Math.ceil(p.total / p.size));
  function link(num, text) {
    if (num < 1 || num > totalPages)
      return '<span aria-disabled="true">' + text + "</span>";
    const q = new URLSearchParams(params);
    q.set("page", num);
    return '<a href="#' + group + "?" + esc(q) + '">' + text + "</a>";
  }
  return (
    '<div class="pagination"><span>共 ' +
    p.total +
    " 条</span><span>" +
    link(p.page - 1, "上一页") +
    "　" +
    p.page +
    " / " +
    totalPages +
    "　" +
    link(p.page + 1, "下一页") +
    "</span></div>"
  );
}
export function ticketsPage(data) {
  const rows = data.page.items.map((t) => [
    '<div class="record"><small>工单 #' +
      t.id +
      '</small><a href="#tickets/' +
      t.id +
      '">' +
      esc(t.title) +
      "</a></div>",
    esc(t.serviceName),
    pill(t.status),
    esc(t.assigneeName || "待分派"),
    time(t.createdAt),
  ]);
  return (
    header(pages.tickets, button("newTicket", "＋ 创建工单", true)) +
    filters("tickets", data) +
    panel(
      "工单列表",
      table(["事件标题", "关联服务", "状态", "处理人", "创建时间"], rows) +
        pagination("tickets", data),
    )
  );
}
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
export function alertsPage(data) {
  const rows = data.page.items.map((a) => [
    pill(a.severity),
    esc(a.title),
    esc(a.serviceName),
    esc(a.source),
    time(a.occurredAt),
    a.ticketId
      ? navLink("tickets/" + a.ticketId, "#" + a.ticketId)
      : button("fromAlert:" + a.id, "创建工单"),
  ]);
  return (
    header(pages.alerts, button("newAlert", "＋ 录入告警", true)) +
    filters("alerts", data) +
    panel(
      "告警记录",
      table(
        ["级别", "告警标题", "服务", "来源", "发生时间", "关联工单"],
        rows,
      ) + pagination("alerts", data),
    )
  );
}
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
              ["操作者", "操作（最近 100 条）", "对象编号", "发生时间"],
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
export function taskRows(data) {
  const rows = data.page.items.map((t) => [
    '<div class="record"><small>' +
      esc(t.serviceName) +
      "</small>" +
      navLink("tasks/" + t.id, "调查 #" + t.id) +
      "</div>",
    '<div class="record">' +
      navLink("tickets/" + t.ticketId, "工单 #" + t.ticketId) +
      "<small>" +
      esc(t.ticketTitle) +
      "</small></div>",
    pill(t.status),
    '<span class="task-summary">' +
      esc(t.summary || t.errorCode || "—") +
      "</span>",
    time(t.createdAt),
  ]);
  return (
    table(["调查任务", "关联工单", "任务状态", "摘要", "创建时间"], rows) +
    pagination("tasks", data)
  );
}
export function tasksPage(data) {
  return (
    header(pages.tasks, button("refresh", "刷新状态")) +
    filters("tasks", data) +
    panel("调查任务", '<div id="task-list">' + taskRows(data) + "</div>")
  );
}
export function taskDetail(t, { tickets, admin, actorId }) {
  const r = t.result,
    mode = t.mode ? esc(label(t.mode)) : "待确认",
    owner = tickets[0]?.assigneeId;
  const summary =
    '<div class="task-meta">' +
    pill(t.status) +
    "<span>" +
    mode +
    "</span>" +
    navLink("tickets/" + t.ticketId, "工单 #" + t.ticketId) +
    "<time>创建于 " +
    time(t.createdAt) +
    "</time></div>";
  const events = [
    ["开始", t.startedAt],
    ["结束", t.finishedAt],
  ]
    .filter(([, date]) => date)
    .map(([name, date]) => "<span>" + name + "　" + time(date) + "</span>")
    .join("");
  let evidence = "",
    analysis = "";
  if (r) {
    const samples = r.evidence
      .map(
        (e) =>
          '<li><span class="evidence-source">' +
          esc(e.source) +
          "</span><p>" +
          esc(e.detail) +
          "</p></li>",
      )
      .join("");
    const snapshot = [
      r.sampledAt ? "采样于 " + time(r.sampledAt) : "",
      r.snapshotId ? "快照 " + esc(r.snapshotId) : "",
    ]
      .filter(Boolean)
      .join("　");
    evidence = panel(
      "现场证据",
      (snapshot ? '<div class="snapshot">' + snapshot + "</div>" : "") +
        '<ol class="evidence">' +
        (samples || "<li>未取得证据</li>") +
        "</ol>",
    );
    analysis = panel(
      {
        offline: "离线规则推断",
        holmes: "HolmesGPT 模型推断",
        模拟数据: "模拟诊断分析",
      }[t.mode] || "诊断分析",
      '<div class="analysis"><section><h3>可能原因</h3><p>' +
        esc(r.possibleCause) +
        "</p></section><section><h3>不确定性</h3><p>" +
        esc(r.uncertainty) +
        "</p></section><section><h3>处理建议（待人工确认）</h3><p>" +
        esc(r.recommendation) +
        "</p></section></div>",
    );
  }
  const error = t.error
    ? '<div class="retry error"><strong>' +
      esc(t.errorCode || "调查失败") +
      "</strong><p>" +
      esc(t.error) +
      "</p></div>"
    : "";
  const pending = ["PENDING", "RUNNING"].includes(t.status)
    ? '<p class="progress">正在等待调查结果，每 5 秒更新一次。</p>'
    : "";
  const feedback = t.feedback
    ? "<p>" + esc(t.feedback) + "</p>"
    : '<p class="muted">暂无人工反馈</p>';
  const canComment =
    !t.feedback &&
    ["SUCCEEDED", "FAILED"].includes(t.status) &&
    (admin || actorId === owner);
  return (
    navLink("tasks", "返回任务列表") +
    header("调查 #" + t.id, button("refresh", "刷新状态")) +
    summary +
    (events ? '<div class="stage-times">' + events + "</div>" : "") +
    error +
    pending +
    (r ? '<div class="investigation">' + evidence + analysis + "</div>" : "") +
    panel(
      "人工反馈",
      '<div class="pad">' + feedback + "</div>",
      canComment ? button("feedback", "填写反馈") : "",
    )
  );
}
export function detail(
  t,
  { users, services, tasks, admin, actorId, relatedTotal },
) {
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
    '<div class="ticket-layout">' +
    panel("处理时间线", '<div class="pad">' + timeline + "</div>") +
    '<div class="ticket-aside">' +
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
        (relatedTotal > related.length
          ? navLink(
              "tasks?ticketId=" + t.id,
              "查看全部 " + relatedTotal + " 项",
            )
          : "") +
        "</div>",
    ) +
    "</div></div>"
  );
}
