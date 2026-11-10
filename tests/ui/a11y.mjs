import { createRequire } from "node:module";
import { readFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const puppeteer = require("puppeteer-core");
const { AxePuppeteer } = require("@axe-core/puppeteer");

const base = process.env.AUDIT_BASE ?? "http://127.0.0.1:47321";
const lines = readFileSync(process.env.AUDIT_ACCESS, "utf8").split("\n").filter(Boolean);
function secret(login) {
  const line = lines.find((row) => row.startsWith(login + " "));
  if (!line) throw new Error("missing login");
  return line.slice(login.length + 1);
}

const browser = await puppeteer.launch({
  executablePath: process.env.CHROME ?? "/usr/local/bin/google-chrome",
  headless: "new",
  args: ["--no-sandbox", "--disable-dev-shm-usage"]
});

async function login(loginName) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push("pageerror " + error.message));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push("console " + message.text());
  });
  await page.goto(base + "/account/login", { waitUntil: "networkidle0" });
  await page.type("#login", loginName);
  await page.type("#password", secret(loginName));
  await Promise.all([
    page.waitForNavigation({ waitUntil: "networkidle0" }),
    page.click("button[type=submit]")
  ]);
  return { page, errors };
}

const failures = [];
const admin = await login("demo-admin");
const caseHref = await admin.page.$eval("a[href^='/cases/']", (el) => el.getAttribute("href"));
const paths = ["/todo", "/cases", caseHref, "/admin", "/account/denied"];
for (const width of [320, 390, 1366]) {
  await admin.page.setViewport({ width, height: 900 });
  for (const path of paths) {
    admin.errors.length = 0;
    const response = await admin.page.goto(base + path, { waitUntil: "networkidle0" });
    const box = await admin.page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth
    }));
    if (box.scroll > box.client + 1)
      failures.push(`${width} ${path} page ${box.scroll}>${box.client}`);
    const axe = await new AxePuppeteer(admin.page)
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .disableRules(["color-contrast"])
      .analyze();
    for (const violation of axe.violations) {
      if (violation.impact === "serious" || violation.impact === "critical" || violation.id === "region" || violation.id === "select-name" || violation.id === "duplicate-id")
        failures.push(`${width} ${path} ${violation.id} ${violation.impact} nodes=${violation.nodes.length}`);
    }
    if (admin.errors.length) failures.push(`${width} ${path} ${admin.errors.join(" | ")}`);
    void response;
  }
}

const staff = await login("demo-staff");
staff.errors.length = 0;
await staff.page.setViewport({ width: 390, height: 900 });
const denied = await staff.page.goto(base + "/admin", { waitUntil: "networkidle0" });
const deniedText = await staff.page.evaluate(() => document.body.innerText);
if (denied.status() !== 200) failures.push("staff admin status " + denied.status());
if (!deniedText.includes("이 화면은 관리자만 엽니다")) failures.push("staff admin missing message");
if (staff.errors.length) failures.push("staff admin " + staff.errors.join(" | "));
const staffBox = await staff.page.evaluate(() => ({
  client: document.documentElement.clientWidth,
  scroll: document.documentElement.scrollWidth
}));
if (staffBox.scroll > staffBox.client + 1) failures.push(`390 /admin 403 page ${staffBox.scroll}>${staffBox.client}`);

await browser.close();
if (failures.length) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log("a11y ok");
