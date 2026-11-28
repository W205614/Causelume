import { chromium, expect } from "@playwright/test";

const { CAUSELUME_URL: origin, CAUSELUME_TEST_SESSION: session, CAUSELUME_TEST_TICKET: ticket,
  CAUSELUME_TEST_ACCESS: access, CAUSELUME_BROWSER: executablePath } = process.env;
if (!origin || !session || !ticket || !["granted", "revoked"].includes(access)) throw Error("Missing isolated collaboration fixture");
const browser = await chromium.launch({ executablePath });
try {
  const context = await browser.newContext();
  await context.addCookies([{ name: "JSESSIONID", value: session, url: origin, httpOnly: true }]);
  const page = await context.newPage();
  await page.goto(origin + "/#collaboration");
  await expect(page.getByRole("heading", { name: "协作工单" })).toBeVisible();
  if (access === "granted") {
    await page.getByRole("link", { name: new RegExp("#" + ticket + " ·") }).click();
    await expect(page.getByRole("heading", { name: "共享调查" })).toBeVisible();
    await expect(page.getByRole("link", { name: /调查 #\d+/ })).toHaveCount(1);
    await page.getByRole("button", { name: "添加协作留言" }).click();
    await page.getByLabel("留言", { exact: true }).fill("Team coordination only");
    await page.getByRole("button", { name: "确认", exact: true }).click();
    await expect(page.getByText("Team coordination only", { exact: true })).toBeVisible();
  } else {
    await expect(page.getByText("暂无协作邀请。")).toBeVisible();
    await page.goto(origin + "/#collaboration/" + ticket);
    await expect(page.getByRole("alert")).toContainText("记录不存在");
  }
  console.log("PASS collaboration browser", access);
} finally {
  await browser.close();
}
