import { demo, request } from "./mock.js";
import {
  esc,
  brand,
  pages,
  icon,
  label,
  overview,
  ticketsPage,
  servicesPage,
  alertsPage,
  usersPage,
  tasksPage,
  detail,
  taskDetail,
  ticketTable,
} from "./views.js";
const root = document.querySelector("#app"),
  dialog = document.querySelector("#dialog");
let me,
  data,
  ticket,
  task,
  epoch = 0;
const route = () => location.hash.slice(1).split("/");
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
    '<label>账号<input name="username" autocomplete="username" required autofocus></label><label>密码<input type="password" name="password" autocomplete="current-password" required></label><p class="error" role="alert"></p><button class="primary">登录</button></form></section></div>';
  root.querySelector("form").onsubmit = async (e) => {
    e.preventDefault();
    const f = e.currentTarget,
      b = f.querySelector("button");
    b.disabled = true;
    try {
      me = await request("/login", Object.fromEntries(new FormData(f)));
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
      .map(
        ([key, title]) =>
          '<a href="#' +
          key +
          '" class="' +
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
async function render() {
  const version = ++epoch;
  if (dialog.open) dialog.close();
  if (!me) {
    login();
    return;
  }
  const [raw, id] = route(),
    page = raw || "overview";
  shell(page);
  const main = root.querySelector("main");
  try {
    const names = ["tickets", "services", "users", "alerts", "tasks"];
    const rows = await Promise.all(names.map((n) => request("/" + n)));
    const t =
      page === "tickets" && id
        ? await request("/tickets/" + encodeURIComponent(id))
        : null;
    if (epoch !== version) return;
    data = Object.fromEntries(names.map((n, i) => [n, rows[i]]));
    data.admin = me.role === "ADMIN";
    ticket = t;
    task =
      page === "tasks" && id
        ? data.tasks.find((j) => String(j.id) === id)
        : null;
    if (page === "tasks" && id && !task) throw Error("调查任务不存在");
    const view = {
      overview,
      services: servicesPage,
      alerts: alertsPage,
      tickets: ticketsPage,
      tasks: tasksPage,
      users: usersPage,
    }[page];
    if (!view) throw Error("页面不存在");
    main.innerHTML = ticket
      ? detail(ticket, data)
      : task
        ? taskDetail(task)
        : view(data);
    const filter = document.querySelector("#filter");
    if (filter)
      filter.oninput = () => {
        const key = filter.value.trim().toLowerCase();
        document.querySelector("#matches").innerHTML = ticketTable(
          data.tickets.filter((t) =>
            (t.title + " " + t.id).toLowerCase().includes(key),
          ),
          data.services,
        );
      };
  } catch (err) {
    if (version !== epoch) return;
    if (err.status === 401) {
      me = null;
      login();
      return;
    }
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
  key +
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
function form(title, fields, send) {
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
    btn.disabled = true;
    try {
      const next = await send(Object.fromEntries(new FormData(f)));
      dialog.close();
      if (typeof next === "string" && location.hash !== "#" + next)
        location.hash = next;
      else await render();
    } catch (err) {
      f.querySelector(".error").textContent = err.message;
    } finally {
      btn.disabled = false;
    }
  };
}
root.onclick = async (e) => {
  const btn = e.target.closest("[data-op]");
  if (!btn) return;
  const [op, arg] = btn.dataset.op.split(":");
  if (op === "refresh") {
    await render();
    return;
  }
  if (op === "logout") {
    try {
      await request("/logout", {});
      me = null;
      await render();
    } catch (err) {
      form("退出失败", "<p>" + esc(err.message) + "</p>", async () => {});
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
    const a = data.alerts.find((a) => String(a.id) === arg),
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
    const fields =
      arg === "ASSIGN"
        ? select(
            "assigneeId",
            "处理人",
            data.users.map((u) => [u.id, u.username]),
          )
        : "";
    form(
      label(arg),
      fields +
        area(
          "note",
          { VERIFY: "恢复验证依据", CLOSE: "复盘记录" }[arg] || "处理说明",
        ),
      async (f) => {
        await request("/tickets/" + ticket.id + "/actions", {
          ...f,
          ...(f.assigneeId ? { assigneeId: Number(f.assigneeId) } : {}),
          version: ticket.version,
          action: arg,
        });
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
      "<p>调查结果将关联工单 #" + ticket.id + "，供人工核查。</p>",
      async () => {
        const t = await request("/tasks", { ticketId: ticket.id, requestId });
        return "tasks/" + t.id;
      },
    );
  }
  if (op === "feedback")
    form("填写人工反馈", area("feedback", "核查结果"), async (f) => {
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
