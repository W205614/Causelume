import { productAction, notificationsPage, budgetPage } from "./product.js";
import { accountPage, accountAction, clearAccountSecrets } from "./account.js";
import { demo, request as apiRequest } from "./api.js";
import {
  esc,
  brand,
  pages,
  icon,
  label,
  overview,
  ticketsPage,
  servicesPage,
  observations,
  alertsPage,
  usersPage,
  tasksPage,
  detail,
  taskDetail,
  taskRows,
} from "./views.js";
const root = document.querySelector("#app"),
  dialog = document.querySelector("#dialog");
let me,
  data,
  ticket,
  task,
  epoch = 0,
  session = 0;

function endSession() {
  session++;
  clearAccountSecrets();
  me = data = ticket = task = null;
  if (dialog.open) dialog.close();
  dialog.replaceChildren();
  document.querySelectorAll(".write-notice").forEach(notice => notice.remove());
  void render();
}

async function request(path, body) {
  const started = session;
  try {
    return await apiRequest(path, body);
  } catch (err) {
    if (err.status === 401 && me && started === session && path !== "/login")
      endSession();
    throw err;
  }
}
const mode = () =>
  '<span class="mode ' +
  (demo ? "warning" : "") +
  '">' +
  (demo ? "模拟数据" : "业务工作区") +
  "</span>";
function login() {
  root.innerHTML =
    '<div class="login"><section class="login-art">' +
    brand +
    '<svg class="diagram" viewBox="0 0 320 135" aria-hidden="true"><g stroke="#557d70"><path d="M0 70h65l35-38h95l33 38h92M65 70l35 38h95l33-38M100 32v76m95-76v76"/></g><path stroke="#9ce8b6" stroke-width="3" d="M0 70h65l35-38h95l33 38h92"/><g fill="#a4eac1" stroke="#102f30" stroke-width="4"><circle cx="65" cy="70" r="7"/><circle cx="100" cy="32" r="7"/><circle cx="195" cy="32" r="7"/><circle cx="228" cy="70" r="7"/></g></svg><h2>故障诊断<br>与协作平台</h2></section><section class="login-pane"><form id="login">' +
    mode() +
    "<h1>登录运维控制台</h1>" +
    (demo
      ? '<p class="muted">演示账号 admin 或 operator，密码 demo。</p>'
      : "") +
    '<label>账号<input name="username" autocomplete="username" required autofocus></label><label>密码<input type="password" name="password" autocomplete="current-password" required></label><label>验证码或恢复码<input name="code" autocomplete="one-time-code" placeholder="已启用二次验证时填写"></label><p class="error" role="alert"></p><button class="primary">登录</button></form></section></div>';
  root.querySelector("form").onsubmit = async (e) => {
    e.preventDefault();
    const f = e.currentTarget,
      b = f.querySelector("button");
    b.disabled = true;
    try {
      me = await request("/login", Object.fromEntries(new FormData(f)));
      session++;
      await render();
    } catch (err) {
      f.querySelector(".error").textContent = err.message;
    } finally {
      b.disabled = false;
    }
  };
}
function shell(page) {
  root.innerHTML =
    '<div class="shell"><aside>' +
    brand +
    '<nav aria-label="主导航">' +
    Object.entries(pages)
      .filter(([key]) => (key !== "budget" || me.role === "ADMIN") && (!me.enrollmentRequired || key === "account"))
      .map(
        ([key, title]) =>
          '<a href="#' +
          key +
          '" ' +
          (page === key ? 'aria-current="page" ' : "") +
          'class="' +
          (page === key ? "active" : "") +
          '">' +
          icon(key) +
          title +
          "</a>",
      )
      .join("") +
    '</nav><div class="identity"><strong>' +
    esc(me.username) +
    '</strong><span class="small">' +
    esc(label(me.role)) +
    '</span><button data-op="logout">退出登录</button></div></aside><div><div class="bar"><span>工作空间　/　' +
    esc(pages[page] || "页面") +
    "</span>" +
    mode() +
    '</div><main><div class="progress">正在加载…</div></main></div></div>';
}
async function loadView(page, id, params) {
  const state = { admin: me.role === "ADMIN", actorId: me.id, params };
  if (page === "account") return { ...state, me };
  if (page === "notifications") return { ...state, notifications: await request("/notifications" + (params.size ? "?" + params : "")) };
  if (page === "budget") return { ...state, budget: await request("/budget") };
  const list = async (name) =>
    request("/" + name + "/page?" + params.toString());
  switch (page) {
    case "overview":
      return { ...state, summary: await request("/overview") };
    case "services": {
      const services = await request("/services");
      if (!id) return { ...state, services };
      const service = services.find((s) => String(s.id) === id);
      if (!service) throw Error("服务不存在");
      const [metrics, logs] = await Promise.all(
        ["metrics", "logs"].map((key) =>
          request("/services/" + service.id + "/" + key),
        ),
      );
      return { ...state, service, metrics, logs };
    }
    case "alerts": {
      const [page, services] = await Promise.all([
        list("alerts"),
        request("/services"),
      ]);
      return { ...state, page, services, alerts: page.items };
    }
    case "tickets": {
      const [services, users] = await Promise.all([
        request("/services"),
        request("/users"),
      ]);
      if (!id)
        return { ...state, services, users, page: await list("tickets") };
      const [ticket, related] = await Promise.all([
        request("/tickets/" + encodeURIComponent(id)),
        request("/tasks/page?ticketId=" + encodeURIComponent(id) + "&size=20"),
      ]);
      if (params.has("timelinePage")) {
        const history = await request("/tickets/" + encodeURIComponent(id) + "/timeline?page=" + encodeURIComponent(params.get("timelinePage")) + "&size=50");
        ticket.timeline = [...history.items].reverse();
      }
      return {
        ...state,
        services,
        users,
        ticket,
        tasks: related.items,
        relatedTotal: related.total,
      };
    }
    case "tasks": {
      if (!id) {
        const [page, services] = await Promise.all([
          list("tasks"),
          request("/services"),
        ]);
        return { ...state, page, services };
      }
      const task = await request("/tasks/" + encodeURIComponent(id));
      const ticket = await request("/tickets/" + task.ticketId);
      return { ...state, task, tickets: [ticket] };
    }
    case "users": {
      const [users, audit] = await Promise.all([
        request("/users"),
        !demo && state.admin ? request("/audit") : null,
      ]);
      return { ...state, users, audit };
    }
    default:
      throw Error("页面不存在");
  }
}
let poll;
function scheduleUpdates(page, id, params, version) {
  clearTimeout(poll);
  if (
    page !== "tasks" ||
    !(id ? [task] : data.page.items).some((t) =>
      ["PENDING", "RUNNING"].includes(t.status),
    )
  )
    return;
  poll = setTimeout(async () => {
    if (epoch !== version) return;
    if (document.hidden || dialog.open) {
      scheduleUpdates(page, id, params, version);
      return;
    }
    try {
      const fresh = id
        ? await request("/tasks/" + encodeURIComponent(id))
        : await request("/tasks/page?" + params);
      if (epoch !== version) return;
      if (id) {
        task = fresh;
        data.task = fresh;
        document.querySelector("main").innerHTML = taskDetail(task, data);
      } else {
        data.page = fresh;
        document.querySelector("#task-list").innerHTML = taskRows(data);
      }
      scheduleUpdates(page, id, params, version);
    } catch (err) {
      if (epoch === version) {
        const message = document.createElement("p");
        message.className = "retry";
        message.setAttribute("role", "alert");
        message.textContent = "自动刷新暂停：" + err.message + "。请手动刷新。";
        document.querySelector("main").prepend(message);
      }
    }
  }, 5000);
}
async function render() {
  clearTimeout(poll);
  const version = ++epoch;
  if (dialog.open) dialog.close();
  if (!me) {
    login();
    return;
  }
  const [path, query = ""] = location.hash.slice(1).split("?"),
    [raw, id] = path.split("/"),
    page = me.enrollmentRequired ? "account" : raw || "overview",
    params = new URLSearchParams(query);
  shell(page);
  const main = root.querySelector("main");
  try {
    const fresh = await loadView(page, id, params);
    if (epoch !== version) return;
    data = fresh;
    ticket = data.ticket;
    task = data.task;
    const views = {
      notifications: notificationsPage,
      budget: budgetPage,
      account: () => accountPage(me),
      overview,
      services: servicesPage,
      alerts: alertsPage,
      tickets: ticketsPage,
      tasks: tasksPage,
      users: usersPage,
    };
    main.innerHTML = ticket
      ? detail(ticket, data)
      : task
        ? taskDetail(task, data)
        : data.service
          ? observations(data.service, data.metrics, data.logs)
          : views[page](data);
    for (const segment of main.querySelectorAll(".track [data-count]"))
      segment.style.flexGrow = segment.dataset.count;
    const filters = main.querySelector("form[data-filter]");
    if (filters)
      filters.onsubmit = (e) => {
        e.preventDefault();
        const query = new URLSearchParams();
        for (const [key, value] of new FormData(filters))
          if (value.trim()) query.set(key, value.trim());
        if (page === "tasks" && params.has("ticketId"))
          query.set("ticketId", params.get("ticketId"));
        location.hash = page + (query.size ? "?" + query : "");
      };
    scheduleUpdates(page, id, params, version);
  } catch (err) {
    if (version !== epoch) return;
    main.innerHTML =
      '<div class="retry"><p class="error" role="alert">' +
      esc(err.message) +
      '</p><button data-op="refresh">重新加载</button></div>';
  }
}
const input = (key, name, type = "text", value = "") =>
  "<label>" +
  name +
  '<input required name="' +
  key +
  '" type="' +
  type +
  '" value="' +
  esc(value) +
  '" maxlength="250"></label>';
const area = (key, name) =>
  "<label>" +
  name +
  '<textarea required name="' +
  key +
  '" maxlength="3000"></textarea></label>';
const select = (key, name, options, chosen) =>
  "<label>" +
  name +
  '<select required name="' +
  key + '" aria-label="' + esc(name) +
  '">' +
  options
    .map(
      ([v, n]) =>
        '<option value="' +
        esc(v) +
        '" ' +
        (v === chosen ? "selected" : "") +
        ">" +
        esc(n) +
        "</option>",
    )
    .join("") +
  "</select></label>";
function reportLate(message) {
  const host = dialog.open ? dialog : document.body;
  const notice = document.createElement("div");
  notice.className = "write-notice";
  notice.setAttribute("role", "status");
  notice.textContent = message + " ";
  const dismiss = document.createElement("button");
  dismiss.type = "button";
  dismiss.textContent = "知道了";
  dismiss.onclick = () => notice.remove();
  notice.append(dismiss);
  host.append(notice);
}
function form(title, fields, send, review) {
  const pageEpoch = epoch,
    owner = me;
  dialog.innerHTML =
    "<form><h2>" +
    title +
    "</h2>" +
    fields +
    '<p class="error" role="alert"></p><div class="actions"><button type="button" data-cancel>取消</button><button class="primary">确认</button></div></form>';
  dialog.showModal();
  dialog.querySelector("[data-cancel]").onclick = () => dialog.close();
  dialog.querySelector("form").onsubmit = async (e) => {
    e.preventDefault();
    const f = e.currentTarget,
      btn = f.querySelector(".primary");
    const current = () =>
      pageEpoch === epoch && dialog.open && dialog.contains(f);
    btn.disabled = true;
    try {
      const next = await send(Object.fromEntries(new FormData(f)));
      if (!current()) {
        if (owner === me)
          reportLate(
            title +
              "已提交" +
              (typeof next === "string" ? "（" + next + "）" : "") +
              "。可刷新原页面核对。",
          );
        return;
      }
      dialog.close();
      if (typeof next === "string" && location.hash !== "#" + next)
        location.hash = next;
      else await render();
    } catch (err) {
      if (!current()) {
        if (owner === me)
          reportLate(
            title +
              (err.status ? "未提交：" : "提交结果未确认，请刷新核对：") +
              err.message,
          );
        return;
      }
      f.querySelector(".error").textContent = err.message;
      if (err.status === 409 && review) {
        try {
          const message = await review(f);
          if (current()) {
            f.querySelector(".error").textContent = message;
            btn.textContent = "重新确认";
          }
        } catch (readError) {
          if (current())
            f.querySelector(".error").textContent =
              "读取最新记录失败，请关闭后刷新核对。" + readError.message;
          btn.dataset.blocked = "1";
        }
      }
    } finally {
      btn.disabled = btn.dataset.blocked === "1";
    }
  };
}
root.onclick = async (e) => {
  const btn = e.target.closest("[data-op]");
  if (!btn) return;
  const [op, arg] = btn.dataset.op.split(":");
  const started = session;
  if (await accountAction(op, { form, input, request, render, me, sameSession: () => started === session, refreshMe: async (logout = false) => {
    if (started !== session) return;
    if (logout) return endSession();
    const fresh = await request("/me");
    if (started === session) me = fresh;
  } })) return;
  const actionEpoch = epoch;
  try {
    if (await productAction(op, arg, { form, input, request, data, ticket, me, render,
      isCurrent: () => epoch === actionEpoch,
      refreshMe: async () => { me = await request("/me"); }
    })) return;
  } catch (err) {
    if (epoch === actionEpoch) reportLate(err.message);
    return;
  }
  if (op === "refresh") {
    await render();
    return;
  }
  if (op === "logout") {
    try {
      await request("/logout", {});
      if (started === session) endSession();
    } catch (err) {
      if (started === session) form("退出失败", "<p>" + esc(err.message) + "</p>", async () => {});
    }
    return;
  }
  const serviceSelect = (chosen) =>
    select(
      "serviceId",
      "关联服务",
      data.services.map((s) => [s.id, s.name]),
      chosen,
    );
  if (op === "newTicket" || op === "fromAlert") {
    if (!data.services) {
      const opening = epoch;
      try {
        const services = await request("/services");
        if (opening !== epoch || dialog.open) return;
        data.services = services;
      } catch (err) {
        if (opening === epoch) reportLate("读取服务失败：" + err.message);
        return;
      }
    }
    const a =
        op === "fromAlert"
          ? data.alerts.find((a) => String(a.id) === arg)
          : null,
      requestId = crypto.randomUUID();
    form(
      "创建工单",
      (a
        ? "<p>告警 #" +
          a.id +
          " · " +
          esc(data.services.find((s) => s.id === a.serviceId)?.name) +
          "</p>"
        : serviceSelect()) + input("title", "事件标题", "text", a?.title),
      async (f) => {
        const t = await request("/tickets", {
          ...f,
          serviceId: a?.serviceId || Number(f.serviceId),
          ...(a ? { alertId: a.id } : {}),
          requestId,
        });
        return "tickets/" + t.id;
      },
    );
  }
  if (op === "action") {
    let reviewed = ticket;
    const fields =
      arg === "ASSIGN"
        ? select(
            "assigneeId",
            "处理人",
            data.users.map((u) => [u.id, u.username]),
          )
        : "";
    const verification = arg === "VERIFY" && !demo ? select("verification", "恢复检测结果", [
      ["UNKNOWN", "未知，仍需核查"], ["PASSED", "通过，已有恢复依据"], ["FAILED", "失败，继续处理"]
    ]) : "";
    const reviewFields = arg === "CLOSE" && !demo ? area("impact", "影响范围") +
      input("startedAt", "故障开始时间", "datetime-local") + input("endedAt", "恢复时间", "datetime-local") +
      area("cause", "原因或尚未确定的原因") + area("uncertainty", "不确定性与证据缺口") : "";
    form(
      label(arg),
      fields + verification + reviewFields +
        area(
          "note",
          { VERIFY: "恢复验证依据", CLOSE: "复盘记录" }[arg] || "处理说明",
        ),
      async (f) => {
        const { impact, startedAt, endedAt, cause, uncertainty, ...values } = f;
        await request("/tickets/" + reviewed.id + "/actions", {
          ...values,
          ...(impact ? { postmortem: { impact, startedAt: new Date(startedAt).toISOString(), endedAt: new Date(endedAt).toISOString(), cause, uncertainty } } : {}),
          ...(f.assigneeId ? { assigneeId: Number(f.assigneeId) } : {}),
          version: reviewed.version,
          action: arg,
        });
      },
      async () => {
        reviewed = await request("/tickets/" + reviewed.id);
        const latest = reviewed.timeline.at(-1);
        const owner =
          data.users?.find((u) => u.id === reviewed.assigneeId)?.username ||
          "待分派";
        return (
          "工单已更新，请复核后重新确认。当前" +
          label(reviewed.status) +
          "，负责人 " +
          owner +
          "，版本 " +
          reviewed.version +
          (latest ? "。最新记录：" + latest.actor + " · " + latest.note : "")
        );
      },
    );
  }
  if (op === "newService")
    form(
      "添加服务",
      input("name", "服务标识") +
        input("description", "服务说明") +
        input("resource", "关联资源"),
      async (f) => {
        await request("/services", f);
      },
    );
  if (op === "newUser")
    form(
      "添加用户",
      input("username", "账号") +
        input("password", "初始密码", "password") +
        select("role", "角色", [
          ["OPERATOR", "运维人员"],
          ["ADMIN", "管理员"],
        ]),
      async (f) => {
        await request("/users", f);
      },
    );
  if (op === "newAlert")
    form(
      "录入告警",
      serviceSelect() +
        input("title", "告警标题") +
        input("source", "来源") +
        input("externalId", "源端事件编号") +
        select("severity", "严重级别", [
          ["WARNING", "警告"],
          ["CRITICAL", "严重"],
          ["INFO", "提示"],
        ]) +
        input("occurredAt", "发生时间", "datetime-local"),
      async (f) => {
        await request("/alerts", {
          ...f,
          serviceId: Number(f.serviceId),
          occurredAt: new Date(f.occurredAt).toISOString(),
        });
      },
    );
  if (op === "investigate") {
    const requestId = crypto.randomUUID();
    form(
      "发起只读调查",
      "<p>调查结果将关联工单 #" +
        ticket.id +
        "，供人工核查。</p>" +
        (demo
          ? "<p>本次使用模拟数据。</p>"
          : "<p>若服务端启用 HolmesGPT，确认后将调用模型，可能产生 API 费用。离线模式使用规则分析。</p>"),
      async () => {
        const t = await request("/tasks", { ticketId: ticket.id, requestId });
        return "tasks/" + t.id;
      },
    );
  }
  if (op === "feedback")
    form("填写人工反馈", area("feedback", "核查结果") + select("rating", "帮助程度", [
      ["HELPFUL", "有帮助"], ["PARTIAL", "部分正确"], ["UNHELPFUL", "无帮助"]
    ]), async (f) => {
      await request("/tasks/" + task.id + "/feedback", f);
    });
};
window.addEventListener("hashchange", render);
try {
  me = await request("/me");
  await render();
} catch (err) {
  if (err.status === 401) login();
  else {
    root.innerHTML =
      '<div class="retry"><p role="alert">' +
      esc(err.message) +
      "</p><button>重试</button></div>";
    root.querySelector("button").onclick = () => location.reload();
  }
}
