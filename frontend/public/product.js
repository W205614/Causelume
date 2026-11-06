import { esc } from "./views.js";

const field = (key, label, value = "", type = "text", required = false) =>
  '<label>' + label + '<input name="' + key + '" type="' + type + '" value="' + esc(value) + '"' +
  (type === "number" ? ' min="0" step="0.000001"' : "") + (required ? " required" : "") + '></label>';
const select = (key, label, options, value) =>
  '<label>' + label + '<select name="' + key + '">' + options.map(([id, name]) =>
    '<option value="' + esc(id) + '"' + (String(value ?? "") === String(id) ? " selected" : "") + '>' + esc(name) + '</option>').join("") + '</select></label>';
const panel = (title, body) => '<section class="panel"><div class="pad"><h2>' + title + '</h2>' + body + '</div></section>';
const button = (op, text) => '<button data-op="' + op + '">' + text + '</button>';
const date = value => value ? esc(new Date(value).toLocaleString("zh-CN")) : "—";

export function notificationsPage(data) {
  const delivery = data.params.get("scope") === "delivery";
  const next = new URLSearchParams(data.params);
  next.set("afterId", data.notifications.nextCursor);
  return '<div class="head"><h1>' + (delivery ? "邮件投递管理" : "通知中心") + '</h1>' +
    (delivery ? '<a href="#notifications">我的通知</a>' : data.admin ? '<a href="#notifications?scope=delivery">邮件投递管理</a>' : "") + button("refresh", "刷新通知") + '</div>' +
    (data.notifications.items.length ? data.notifications.items.map(n => panel(
      esc(n.eventType) + " · " + (n.readAt ? "已读" : "未读"),
      '<p>' + esc(n.message) + '</p><p>' + date(n.createdAt) + ' · 邮件状态 ' + esc(n.mailStatus) +
      ' · 尝试 ' + esc(n.attempts) + ' 次</p>' + (n.lastError ? '<p role="alert">' + esc(n.lastError) + '</p>' : "") +
      '<a href="#tickets/' + n.ticketId + '">查看关联工单</a> ' +
      (!n.readAt && (!delivery || n.recipientId === data.actorId) ? button("notificationRead:" + n.id, "标记已读") : "") +
      (data.admin && ["FAILED", "UNKNOWN"].includes(n.mailStatus) ? button("notificationResend:" + n.id, "人工重发邮件") : "")
    )).join("") : '<p class="empty">暂无通知</p>') +
    (data.notifications.nextCursor ? '<a href="#notifications?' + esc(next.toString()) + '">更早通知</a>' : "");
}

export function budgetPage({ budget }) {
  return '<h1>模型预算</h1>' + panel("预留与核对",
    '<p>模式 ' + esc(budget.mode) + ' · 每日额度 $' + esc(budget.dailyBudgetUsd ?? "未配置") +
    ' · 每项预留 $' + esc(budget.reservationUsd ?? "未配置") + '</p>' +
    '<p>预留用于限制可发起的调查。供应商最终账单需核对，结果未知时额度继续保留。</p>' +
    budget.reservations.map(r => '<p><a href="#tasks/' + r.taskId + '">调查 #' + r.taskId +
      '</a> · ' + esc(r.state) + ' · 预留 $' + esc(r.reservedUsd) + ' · 实际 ' +
      esc(r.actualUsd ?? "待核对") + (r.state === "UNKNOWN" ? button("settle:" + r.taskId, "核对费用") : "") + '</p>').join(""));
}

export async function productAction(op, arg, context) {
  const { request, form, data, ticket, me } = context;
  if (op === "notificationRead") {
    await request("/notifications/" + arg + "/read", {});
    if (context.isCurrent()) await context.render();
  } else if (op === "notificationResend") {
    form("人工重发邮件", '<p>此前邮件可能已送达。重发可能出现重复。</p>' + field("reason", "重发原因", "", "text", true),
      values => request("/notifications/" + arg + "/resend", values));
  } else if (op === "settle") {
    form("核对调查费用", field("actualCostUsd", "供应商实际费用（美元）", "", "number", true) + field("reason", "核对依据", "", "text", true),
      values => request("/budget/" + arg + "/reconcile", { ...values, actualCostUsd: Number(values.actualCostUsd) }).then(() => "budget"));
  } else if (op === "serviceEdit") {
    let service = data.services.find(s => s.id === Number(arg));
    const sources = await request("/sources");
    const users = await request("/users");
    if (!context.isCurrent()) return true;
    form("编辑服务",
      field("name", "服务名称", service.name, "text", true) + field("description", "描述", service.description) +
      field("resource", "资源标识", service.resource, "text", true) + field("environment", "环境", service.environment || "default", "text", true) +
      field("serviceLabel", "观测服务标签", service.serviceLabel || service.name) +
      field("runbook", "运行手册链接", service.runbook || "", "url") +
      select("sourceId", "批准的数据源", [["", "未接入"], ...(sources.sources || sources).map(s => [s.id, s.name])], service.sourceId) +
      select("ownerId", "默认负责人", [["", "未指定"], ...users.filter(u => u.active !== false).map(u => [u.id, u.username])], service.ownerId) +
      select("active", "服务状态", [["true", "启用"], ["false", "停用"]], service.active !== false),
      values => request("/services/" + arg, { ...values, active: values.active === "true", ownerId: values.ownerId ? Number(values.ownerId) : null, sourceId: values.sourceId || null, version: service.version || 0 }).then(() => "services"),
      async editor => {
        const latest = (await request("/services")).find(s => s.id === service.id);
        if (!latest) throw Error("服务已不存在。");
        const changes = [];
        for (const control of editor.elements) {
          if (!control.name) continue;
          const key = control.name;
          const old = String(service[key] ?? ""), fresh = String(latest[key] ?? "");
          if (old === fresh) continue;
          if (control.value === old) {
            if (control instanceof HTMLSelectElement && ![...control.options].some(option => option.value === fresh))
              throw Error("最新选项已变化，请重新打开服务编辑。");
            control.value = fresh;
          }
          changes.push(control.labels[0].firstChild.textContent + "：" + (fresh || "未设置"));
        }
        service = latest;
        return "服务已更新，请复核后重新确认。版本 " + latest.version + "。" + changes.join("；");
      });
  } else if (op === "sourceCheck") {
    const result = await request("/services/" + arg + "/source-check");
    if (!context.isCurrent()) return true;
    form("连接检测", '<pre>' + esc(JSON.stringify(result, null, 2)) + '</pre>', async () => "services");
  } else if (op === "maintenance") {
    form("维护静默", field("until", "静默截止时间", "", "datetime-local", true) + field("reason", "维护原因", "", "text", true),
      values => request("/services/" + arg + "/maintenance", { ...values, until: new Date(values.until).toISOString() }).then(() => "services"));
  } else if (op === "maintenanceEnd") {
    form("结束维护静默", "<p>结束后新的告警会正常通知。</p>", () => request("/services/" + arg + "/maintenance/end", {}).then(() => "services"));
  } else if (op === "priority" || op === "reopen") {
    const priority = op === "priority";
    let reviewed = ticket;
    form(priority ? "调整优先级" : "重新打开工单",
      (priority ? select("priority", "优先级", ["P1", "P2", "P3", "P4"].map(v => [v, v]), ticket.priority || "P3") : "") +
      field("note", priority ? "调整依据" : "重新打开原因", "", "text", true),
      values => request("/tickets/" + ticket.id + "/actions", { ...values, action: priority ? "PRIORITY" : "REOPEN", version: reviewed.version }).then(() => "tickets/" + ticket.id),
      async () => {
        reviewed = await request("/tickets/" + ticket.id);
        return "工单已更新，请复核后重新确认。当前 " + reviewed.status + "，优先级 " + reviewed.priority + "，版本 " + reviewed.version;
      });
  } else if (op === "improvement") {
    form("添加改进事项", field("title", "事项", "", "text", true) + field("dueDate", "截止日期", "", "date", true) +
      select("ownerId", "负责人", data.users.filter(u => u.active !== false).map(u => [u.id, u.username]), me.id),
      values => request("/tickets/" + ticket.id + "/improvements", { ...values, ownerId: Number(values.ownerId) }).then(() => "tickets/" + ticket.id));
  } else if (op === "improvementDone") {
    await request("/tickets/" + ticket.id + "/improvements/" + arg, { status: "DONE" });
    if (context.isCurrent()) await context.render();
  } else if (op === "userStatus") {
    const user = data.users.find(u => u.id === Number(arg));
    form(user.active === false ? "启用账号" : "停用账号", "<p>账号状态变化会撤销已有会话。</p>",
      () => request("/users/" + arg + "/status", { active: user.active === false }).then(() => "users"));
  } else if (op === "userPassword") {
    form("重置用户密码", field("newPassword", "新密码（至少12字符）", "", "password", true),
      values => request("/users/" + arg + "/password", values).then(() => "users"));
  } else if (op === "profile") {
    form("通知邮箱", field("email", "邮箱", me.email || "", "email", true), async values => {
      await request("/account/profile", values);
      await context.refreshMe();
      return "account";
    });
  } else if (op === "retention") {
    const preview = await request("/retention/preview?days=90");
    if (!context.isCurrent()) return true;
    form("90天历史保留预览", "<p>此操作仅统计。实际清理需要另行确认保留策略和备份。</p><pre>" + esc(JSON.stringify(preview, null, 2)) + "</pre>", async () => "users");
  } else return false;
  return true;
}
