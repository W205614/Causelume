import { esc, label } from "./views.js";

const button = (op, text) => '<button data-op="' + op + '">' + text + '</button>';

export function teamsPage({ teams, members, admin }) {
  const selected = members?.team;
  const heading = '<div class="head"><h1>团队管理</h1>' + (admin ? button("teamCreate", "创建团队") : "") + '</div>';
  const teamLinks = teams.map(t => '<li>' + (admin || t.teamRole === "MANAGER"
    ? '<a href="#teams/' + t.id + '">' + esc(t.name) + '</a>'
    : esc(t.name)) + ' · ' + esc(t.slug) + '</li>').join("");
  if (!selected) return heading + (teamLinks ? '<ul>' + teamLinks + '</ul>' : '<p>暂无可访问团队。</p>');
  return heading + '<p><a href="#teams">返回团队列表</a></p><section class="panel"><div class="pad"><h2>' +
    esc(selected.name) + '</h2>' + button("teamMember", "添加或更新成员") + '<ul>' + members.items.map(m =>
      '<li>' + esc(m.username) + ' · ' + (m.teamRole === "MANAGER" ? "团队管理员" : "成员") +
      (m.active ? "" : " · 已停用") + (admin || m.teamRole !== "MANAGER" ? " " + button("teamRemove:" + m.id, "移除成员") : "") +
      '</li>').join("") + '</ul></div></section>';
}

export function collaborationPage(data) {
  if (!data.shared) {
    return '<h1>协作工单</h1>' + (data.collaborations.length
      ? '<ul>' + data.collaborations.map(t => '<li><a href="#collaboration/' + t.id + '">#' + t.id +
        ' · ' + esc(t.title) + '</a> · ' + esc(label(t.status)) + '</li>').join("") + '</ul>'
      : '<p>暂无协作邀请。</p>');
  }
  const t = data.shared;
  return '<p><a href="#collaboration">返回协作工单</a></p><div class="head"><h1>#' + t.id + ' ' + esc(t.title) +
    '</h1>' + (t.status === "CLOSED" ? "" : button("collabNote", "添加协作留言")) + '</div><p>' +
    esc(label(t.status)) + ' · ' + esc(t.priority) + '</p><section class="panel"><div class="pad"><h2>共享调查</h2>' +
    (t.tasks.length ? '<ul>' + t.tasks.map(task => '<li><a href="#tasks/' + task.id + '">调查 #' + task.id +
      '</a> ' + esc(task.summary) + '</li>').join("") + '</ul>' : '<p>暂无共享调查。</p>') +
    '<h2>协作留言</h2>' + (t.notes.length ? '<ul>' + t.notes.map(note =>
      '<li><strong>' + esc(note.actor) + '</strong><p>' + esc(note.note) + '</p></li>').join("") +
      '</ul>' : '<p>暂无协作留言。</p>') + '</div></section>';
}

export async function teamAction(op, arg, context) {
  const { data, ticket, form, request, input, select, area, isCurrent } = context;
  if (op === "teamCreate") {
    form("创建团队", input("name", "团队名称") + input("slug", "团队标识"),
      async fields => "teams/" + (await request("/teams", fields)).id);
  } else if (op === "teamMember") {
    const roles = data.admin ? [["MEMBER", "成员"], ["MANAGER", "团队管理员"]] : [["MEMBER", "成员"]];
    form("添加或更新成员", input("username", "现有平台账号") + select("role", "团队角色", roles),
      fields => request("/teams/" + data.members.team.id + "/members", fields));
  } else if (op === "teamRemove") {
    const member = data.members.items.find(m => m.id === Number(arg));
    const choices = data.members.items.filter(m => m.id !== member.id && m.active).map(m => [m.id, m.username]);
    form("移除团队成员", '<p>移除账号 ' + esc(member.username) + ' 的本队权限，并交接其未完成工作。</p>' +
      select("successorId", "接手人", [["", "待分派"], ...choices]).replace("<select required", "<select"),
      fields => request("/teams/" + data.members.team.id + "/members/" + member.id + "/remove",
        { successorId: fields.successorId ? Number(fields.successorId) : null }));
  } else if (op === "invite") {
    const available = data.tasks.filter(t => t.status === "SUCCEEDED");
    const fields = available.map(t => '<label><input type="checkbox" name="task-' + t.id + '"> 共享调查 #' + t.id + '</label>').join("");
    form("邀请跨团队协作", input("username", "协作账号") + fields,
      values => request("/tickets/" + ticket.id + "/invites", {
        username: values.username, taskIds: available.filter(t => values["task-" + t.id]).map(t => t.id),
      }));
  } else if (op === "inviteRevoke") {
    form("撤销协作邀请", "<p>受邀账号将失去该工单和共享调查的访问权限。</p>",
      () => request("/tickets/" + ticket.id + "/invites/" + arg + "/revoke", {}));
  } else if (op === "collabNote") {
    const id = data.shared?.id ?? ticket.id;
    form("添加协作留言", area("note", "留言", 2000),
      fields => request("/collaborations/" + id + "/notes", fields));
  } else if (op === "improvementAssign") {
    if (!isCurrent()) return true;
    form("交接改进事项", select("ownerId", "接手人", data.users.filter(u => u.active !== false).map(u => [u.id, u.username])),
      fields => request("/tickets/" + ticket.id + "/improvements/" + arg + "/assign", { ownerId: Number(fields.ownerId) }));
  } else return false;
  return true;
}
