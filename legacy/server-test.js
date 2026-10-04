/* 后端集成测试：node test/server-test.js（自动起停临时 server.js 实例，数据写入系统临时目录） */
"use strict";
const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const PORT = 8791;
const BASE = "http://127.0.0.1:" + PORT;
const DATA = fs.mkdtempSync(path.join(os.tmpdir(), "chem-test-"));
const USER = "smoketest用户";
const PASS = "passw0rd";
let saveUpdatedAt = 0;

let failed = 0;
function assert(c, m) {
  if (c) console.log("ok  -", m);
  else { console.error("FAIL:", m); failed++; }
}
async function api(pathname, opts = {}, token) {
  const headers = Object.assign({ "Content-Type": "application/json" }, opts.body ? {} : {});
  if (token) headers.Authorization = "Bearer " + token;
  const r = await fetch(BASE + pathname, Object.assign({}, opts, { headers }));
  const text = await r.text();
  try { return { status: r.status, json: JSON.parse(text) }; }
  catch (e) { return { status: r.status, text }; }
}
const goodSave = (coins) => ({ v: 2, coins, level: 3, bag: { "H2|0": 2 }, discovered: { H2O: { times: 1, first: 1 } } });

async function waitForServer() {
  for (let i = 0; i < 50; i++) {
    try { const r = await fetch(BASE + "/index.html"); if (r.status === 200) return; }
    catch (e) { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error("server did not start");
}

(async function main() {
  const child = spawn(process.execPath, [path.join(__dirname, "..", "server.js"), String(PORT)],
    { env: Object.assign({}, process.env, { CHEM_DATA: DATA }), stdio: "ignore" });
  try {
    await waitForServer();

    /* 1. 静态托管与路径防护 */
    const home = await fetch(BASE + "/");
    assert(home.status === 200 && /化学实验室/.test(await home.text()), "GET / 返回 index.html");
    const trav = await fetch(BASE + "/%2e%2e/%2e%2e/etc/passwd");
    assert(trav.status === 403 || trav.status === 404, "目录穿越被拒绝: " + trav.status);
    const dataFile = await fetch(BASE + "/server-data/users/x.json");
    assert(dataFile.status === 403, "server-data 直接访问 403");

    /* 2. 注册校验 */
    let r = await api("/api/register", { method: "POST", body: JSON.stringify({ user: "a", pass: "whatever" }) });
    assert(!r.json.ok, "过短用户名被拒");
    r = await api("/api/register", { method: "POST", body: JSON.stringify({ user: USER, pass: "123" }) });
    assert(!r.json.ok, "弱密码被拒");
    r = await api("/api/register", { method: "POST", body: JSON.stringify({ user: USER, pass: PASS }) });
    assert(r.json.ok && /^[0-9a-f]{48}$/.test(r.json.token), "注册成功并签发 token");
    let token = r.json.token;
    r = await api("/api/register", { method: "POST", body: JSON.stringify({ user: USER, pass: PASS }) });
    assert(!r.json.ok, "重复注册被拒");

    /* 3. 登录 */
    r = await api("/api/login", { method: "POST", body: JSON.stringify({ user: USER, pass: "WRONGpass" }) });
    assert(!r.json.ok, "错误密码登录被拒");
    r = await api("/api/login", { method: "POST", body: JSON.stringify({ user: USER, pass: PASS }) });
    assert(r.json.ok, "正确密码登录成功");
    const token2 = r.json.token;

    /* 4. 未授权访问 */
    r = await api("/api/me");
    assert(r.status === 401, "无 token 访问 /api/me → 401");
    r = await api("/api/me", {}, "0".repeat(48));
    assert(r.status === 401, "伪造 token → 401");

    /* 5. 存档校验与冲突协议 */
    r = await api("/api/save", { method: "PUT", body: JSON.stringify({ save: { v: 2, coins: "x" } }) }, token);
    assert(!r.json.ok, "缺字段的坏存档被拒");
    r = await api("/api/save", {}, token);
    assert(!r.json.ok, "云端无存档时 GET 返回提示");
    r = await api("/api/save", { method: "PUT", body: JSON.stringify({ save: goodSave(500), base: 0 }) }, token);
    assert(r.json.ok && r.json.updatedAt > 0, "首次上传成功");
    saveUpdatedAt = r.json.updatedAt;
    await new Promise((res) => setTimeout(res, 5));
    r = await api("/api/save", { method: "PUT", body: JSON.stringify({ save: goodSave(1), base: 0 }) }, token);
    assert(r.json.ok === false && r.json.conflict && r.json.updatedAt === saveUpdatedAt, "旧 base 上传 → 冲突（不落库）");
    r = await api("/api/save", { method: "PUT", body: JSON.stringify({ save: goodSave(2), base: saveUpdatedAt }) }, token);
    assert(r.json.ok, "base 与云端一致 → 正常上传");
    saveUpdatedAt = r.json.updatedAt;
    await new Promise((res) => setTimeout(res, 5));
    r = await api("/api/save", { method: "PUT", body: JSON.stringify({ save: goodSave(3), base: 1, force: true }) }, token);
    assert(r.json.ok, "force 强制覆盖成功");
    r = await api("/api/save", {}, token);
    assert(r.json.ok && r.json.save.coins === 3, "GET 取回最新存档");

    /* 6. 修改密码 */
    r = await api("/api/pass", { method: "POST", body: JSON.stringify({ old: "badold1", new: "newpass1" }) }, token);
    assert(!r.json.ok, "原密码错误被拒");
    r = await api("/api/pass", { method: "POST", body: JSON.stringify({ old: PASS, new: "newpass1" }) }, token);
    assert(r.json.ok, "改密成功");
    r = await api("/api/me", {}, token2);
    assert(r.status === 401, "改密后其他设备 token 失效");
    r = await api("/api/me", {}, token);
    assert(r.json.ok, "当前设备 token 保留");
    r = await api("/api/login", { method: "POST", body: JSON.stringify({ user: USER, pass: PASS }) });
    assert(!r.json.ok, "旧密码不再可登录");
    r = await api("/api/login", { method: "POST", body: JSON.stringify({ user: USER, pass: "newpass1" }) });
    assert(r.json.ok, "新密码可登录");
    token = r.json.token;

    /* 7. 注销账号 */
    r = await api("/api/delete", { method: "POST", body: JSON.stringify({ pass: "wrong" }) }, token);
    assert(!r.json.ok, "注销需正确密码");
    r = await api("/api/delete", { method: "POST", body: JSON.stringify({ pass: "newpass1" }) }, token);
    assert(r.json.ok, "注销成功");
    assert(!fs.existsSync(path.join(DATA, USER + ".json")), "账号文件已删除");
    r = await api("/api/me", {}, token);
    assert(r.status === 401, "注销后 token 失效");
    r = await api("/api/login", { method: "POST", body: JSON.stringify({ user: USER, pass: "newpass1" }) });
    assert(!r.json.ok, "注销后无法登录");

    /* 8. 存档大小限制 */
    r = await api("/api/register", { method: "POST", body: JSON.stringify({ user: "bigsave", pass: PASS }) });
    token = r.json.token;
    r = await api("/api/save", { method: "PUT", body: JSON.stringify({ save: Object.assign(goodSave(1), { x: "a".repeat(210000) }) }) }, token);
    assert(!r.json.ok && /过大/.test(r.json.msg || ""), "超大存档被拒");
  } catch (e) {
    console.error("FAIL: 测试执行异常", e);
    failed++;
  } finally {
    child.kill();
    try { fs.rmSync(DATA, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }
  console.log(failed ? "SERVER TEST FAIL (" + failed + ")" : "SERVER TEST PASS");
  process.exit(failed ? 1 : 0);
})();
