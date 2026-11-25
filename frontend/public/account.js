import { demo } from "./api.js";
import { esc } from "./views.js";

let enrollment, recoveryCodes;
export function clearAccountSecrets() {
  enrollment = undefined;
  recoveryCodes = undefined;
}
export function accountPage(me) {
  if (demo) return '<h1>账户安全</h1><p class="empty">当前为内存模拟工作区。验证器绑定和密码管理在真实业务工作区提供。</p>';
  return '<div class="head"><h1>账户安全</h1></div>' +
    (me.enrollmentRequired ? '<p class="retry">请先完成二次验证绑定，再使用工作区。</p>' : "") +
    '<section class="panel"><div class="pad"><h2>登录保护</h2><p>当前账号 ' + esc(me.username) +
    ' · 二次验证' + (me.mfaEnabled ? "已启用" : "尚未绑定") + '</p>' +
    '<button data-op="mfaEnroll" class="primary">' + (me.mfaEnabled ? "换绑验证器" : "绑定验证器") + '</button>' +
    (!me.enrollmentRequired ? '<button data-op="profile">通知邮箱</button><button data-op="changePassword">修改密码</button>' : "") +
    (enrollment ? '<div class="snapshot"><p>在验证器中手动添加以下密钥，再输入验证码确认。</p><code>' + esc(enrollment.secret) +
      '</code><p><button data-op="mfaConfirm">输入验证码完成绑定</button></p></div>' : "") +
    (recoveryCodes ? '<div class="retry"><h3>保存一次性恢复码</h3><p>设备丢失时可使用。请存入个人密码管理器，本页面仅展示这一次。</p><pre>' +
      recoveryCodes.map(esc).join("\n") + '</pre><button data-op="mfaSaved">已安全保存</button></div>' : "") +
    '</div></section>';
}
export async function accountAction(op, { form, input, request, refreshMe, render, me, sameSession }) {
  if (op === "mfaEnroll") {
    const currentFactor = me.mfaEnabled
      ? '<label>现有验证码或未使用的恢复码<input name="code" autocomplete="one-time-code"></label><p>若刚通过恢复码登录，可在五分钟内留空。</p>'
      : "";
    form(me.mfaEnabled ? "换绑验证器" : "绑定验证器", input("password", "当前密码", "password") + currentFactor, async values => {
      const response = await request("/account/mfa/enroll", values);
      if (!sameSession()) return;
      enrollment = response;
      return "account";
    });
  } else if (op === "mfaConfirm") {
    form("验证二次验证器", input("code", "六位验证码"), async values => {
      const response = await request("/account/mfa/confirm", values);
      if (!sameSession()) return;
      recoveryCodes = response.recoveryCodes;
      enrollment = undefined;
      await refreshMe();
      return "account";
    });
  } else if (op === "mfaSaved") {
    clearAccountSecrets();
    await render();
  } else if (op === "profile") {
    form("通知邮箱", input("email", "邮箱", "email", me.email || ""), async values => {
      await request("/account/profile", values);
      await refreshMe();
      return "account";
    });
  } else if (op === "changePassword") {
    form("修改密码", input("currentPassword", "当前密码", "password") + input("newPassword", "新密码（至少12字符）", "password"), async values => {
      await request("/account/password", values);
      await refreshMe(true);
    });
  } else return false;
  return true;
}
