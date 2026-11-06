import { chromium, expect } from "@playwright/test";
import crypto from "node:crypto";

function totp(secret) {
  const alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits="";
  for(const char of secret.replace(/=+$/,"")) bits+=alphabet.indexOf(char).toString(2).padStart(5,"0");
  const bytes=[];
  for(let i=0;i+8<=bits.length;i+=8) bytes.push(parseInt(bits.slice(i,i+8),2));
  const counter=Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now()/30000)));
  const hash=crypto.createHmac("sha1",Buffer.from(bytes)).update(counter).digest();
  const offset=hash[19]&15;
  return String((hash.readUInt32BE(offset)&0x7fffffff)%1000000).padStart(6,"0");
}
const browser=await chromium.launch({executablePath:process.env.CAUSELUME_BROWSER});
try {
  const page=await browser.newPage();
  await page.goto(process.env.CAUSELUME_URL);
  await page.getByLabel("账号",{exact:true}).fill("admin");
  await page.getByLabel("密码",{exact:true}).fill(process.env.CAUSELUME_ADMIN_PASSWORD);
  await page.getByRole("button",{name:"登录",exact:true}).click();
  await expect(page.getByRole("heading",{name:"运维概览"})).toBeVisible();
  await page.getByRole("link",{name:"账户安全",exact:true}).click();
  await page.getByRole("button",{name:"通知邮箱",exact:true}).click();
  await page.getByLabel("邮箱",{exact:true}).fill("owner@example.invalid");
  await page.getByRole("button",{name:"确认",exact:true}).click();
  await expect(page.getByRole("heading",{name:"账户安全"})).toBeVisible();
  await page.getByRole("link",{name:"通知中心",exact:true}).click();
  await expect(page.getByRole("heading",{name:"通知中心"})).toBeVisible();
  await page.getByRole("link",{name:"模型预算",exact:true}).click();
  await expect(page.getByRole("heading",{name:"模型预算"})).toBeVisible();
  await page.getByRole("link",{name:"账户安全",exact:true}).click();
  await page.getByRole("button",{name:"绑定验证器",exact:true}).click();
  await page.getByLabel("当前密码",{exact:true}).fill(process.env.CAUSELUME_ADMIN_PASSWORD);
  await page.getByRole("button",{name:"确认",exact:true}).click();
  await expect(page.locator("code")).toBeVisible();
  const secret=await page.locator("code").textContent();
  await page.getByRole("button",{name:"输入验证码完成绑定",exact:true}).click();
  await page.getByLabel("六位验证码",{exact:true}).fill(totp(secret));
  await page.getByRole("button",{name:"确认",exact:true}).click();
  await expect(page.getByRole("heading",{name:"保存一次性恢复码",exact:true})).toBeVisible();
  await page.getByRole("button",{name:"已安全保存",exact:true}).click();
  await expect(page.getByText("二次验证已启用",{exact:false})).toBeVisible();
  console.log("PASS: real account profile, notification/budget pages, TOTP enrollment and recovery-code acknowledgement");
} finally { await browser.close(); }
