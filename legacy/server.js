/* server.js —— 零依赖 Node 后端：静态托管 + 账号注册/登录 + 云存档 API
 * 运行：node server.js [端口]   （默认 8124，然后访问 http://localhost:8124）
 * 数据保存在 ./server-data/users/<用户>.json（首次运行自动创建）
 * API：
 *   POST /api/register {user, pass} -> {ok, token, user}
 *   POST /api/login    {user, pass} -> {ok, token, user}
 *   GET  /api/me       (Bearer)     -> {ok, user, hasSave, updatedAt}
 *   GET  /api/save     (Bearer)     -> {ok, save, updatedAt}
 *   PUT  /api/save     (Bearer) {save, base?, force?} -> {ok, updatedAt} | {ok:false, conflict:true, updatedAt}
 *        base = 客户端上次同步到的 updatedAt；云端更新而 base 落后时返回冲突（force 强制覆盖）
 *   POST /api/pass     (Bearer) {old, new}  -> {ok}（其余设备登录态失效）
 *   POST /api/delete   (Bearer) {pass}      -> {ok}（注销账号与云端存档）
 *   POST /api/logout   (Bearer)             -> {ok}
 */
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const os = require("os");
const { URL } = require("url");

const PORT = +(process.argv[2] || process.env.PORT || 8124);
const ROOT = __dirname;
const DATA_DIR = process.env.CHEM_DATA || path.join(ROOT, "server-data", "users");
const MAX_BODY = 256 * 1024;
const MAX_SAVE = 200 * 1024;
const TOKEN_TTL = 30 * 864e5;

fs.mkdirSync(DATA_DIR, { recursive: true });

/* ---------- 用户存储 ---------- */
function safeName(u) {
  if (typeof u !== "string" || !/^[\w\u4e00-\u9fa5-]{2,24}$/.test(u)) return null;
  const f = path.join(DATA_DIR, u + ".json");
  if (!f.startsWith(DATA_DIR + path.sep)) return null;
  return f;
}
function readUser(u) {
  const f = safeName(u);
  if (!f) return null;
  try { return JSON.parse(fs.readFileSync(f, "utf8")); } catch (e) { return null; }
}
function writeUser(u, obj) {
  const f = safeName(u);
  if (!f) return false;
  const tmp = f + "." + process.pid + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(obj));
  fs.renameSync(tmp, f);
  return true;
}
function hashPass(pass, salt) {
  return crypto.scryptSync(pass, salt, 64).toString("hex");
}
function verifyPass(pass, salt, hash) {
  const a = Buffer.from(hashPass(pass, salt), "hex");
  const b = Buffer.from(hash, "hex");
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function newToken(user) {
  const t = crypto.randomBytes(24).toString("hex");
  const obj = readUser(user);
  obj.tokens = obj.tokens || {};
  const now = Date.now();
  Object.keys(obj.tokens).forEach((k) => { if (now - obj.tokens[k] > TOKEN_TTL) delete obj.tokens[k]; });
  const keys = Object.keys(obj.tokens);
  if (keys.length > 20) keys.slice(0, keys.length - 20).forEach((k) => delete obj.tokens[k]);
  obj.tokens[t] = now;
  writeUser(user, obj);
  return t;
}
function auth(req) {
  const h = req.headers.authorization || "";
  const m = h.match(/^Bearer ([0-9a-f]{48})$/);
  if (!m) return null;
  const cached = auth.map.get(m[1]);
  if (cached && Date.now() - cached.at < TOKEN_TTL) return cached.user;
  for (const f of fs.readdirSync(DATA_DIR)) {
    if (!f.endsWith(".json")) continue;
    let obj;
    try { obj = JSON.parse(fs.readFileSync(path.join(DATA_DIR, f), "utf8")); } catch (e) { continue; }
    const issued = obj.tokens && obj.tokens[m[1]];
    if (issued && Date.now() - issued < TOKEN_TTL) {
      auth.map.set(m[1], { user: obj.user, at: issued });
      return obj.user;
    }
  }
  return null;
}
auth.map = new Map();

/* ---------- 简易限流（每 IP 每分钟最多 30 次认证写操作） ---------- */
const rl = new Map();
function limited(ip) {
  const now = Date.now();
  const win = rl.get(ip) || [];
  const fresh = win.filter((t) => now - t < 60000);
  if (fresh.length >= 30) { rl.set(ip, fresh); return true; }
  fresh.push(now);
  rl.set(ip, fresh);
  return false;
}
setInterval(() => rl.clear(), 120000).unref();

/* ---------- 工具 ---------- */
function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error("body too large")); req.destroy(); return; }
      chunks.push(c);
    });
    req.on("end", () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}")); }
      catch (e) { reject(new Error("bad json")); }
    });
    req.on("error", reject);
  });
}

/* ---------- 存档结构校验（服务端最小防线） ---------- */
function saveShapeOk(s) {
  if (!s || typeof s !== "object" || Array.isArray(s)) return false;
  if (typeof s.v !== "number") return false;
  if (typeof s.coins !== "number" || s.coins < 0) return false;
  if (typeof s.level !== "number" || s.level < 1) return false;
  if (!s.bag || typeof s.bag !== "object" || Array.isArray(s.bag)) return false;
  if (!s.discovered || typeof s.discovered !== "object" || Array.isArray(s.discovered)) return false;
  return true;
}

/* ---------- API ---------- */
async function handleApi(req, res, pathname) {
  const ip = req.socket.remoteAddress || "?";
  if (pathname === "/api/register" && req.method === "POST") {
    if (limited(ip)) return json(res, 429, { ok: false, msg: "操作太频繁，请稍后再试" });
    const { user, pass } = await readBody(req);
    if (!/^[\w\u4e00-\u9fa5-]{2,24}$/.test(String(user || ""))) return json(res, 200, { ok: false, msg: "用户名需 2-24 位（字母/数字/下划线/中文）" });
    if (typeof pass !== "string" || pass.length < 6 || pass.length > 64) return json(res, 200, { ok: false, msg: "密码需 6-64 位" });
    if (readUser(user)) return json(res, 200, { ok: false, msg: "该用户名已注册" });
    const salt = crypto.randomBytes(16).toString("hex");
    if (!writeUser(user, { user, salt, hash: hashPass(pass, salt), createdAt: Date.now(), tokens: {}, save: null, updatedAt: 0 }))
      return json(res, 500, { ok: false, msg: "存储失败" });
    return json(res, 200, { ok: true, user, token: newToken(user) });
  }
  if (pathname === "/api/login" && req.method === "POST") {
    if (limited(ip)) return json(res, 429, { ok: false, msg: "操作太频繁，请稍后再试" });
    const { user, pass } = await readBody(req);
    const obj = readUser(user);
    if (!obj || typeof pass !== "string" || !verifyPass(pass, obj.salt, obj.hash))
      return json(res, 200, { ok: false, msg: "用户名或密码错误" });
    return json(res, 200, { ok: true, user, token: newToken(user) });
  }
  const me = auth(req);
  if (!me) return json(res, 401, { ok: false, msg: "未登录或登录已过期" });
  const obj = readUser(me);
  if (!obj) return json(res, 401, { ok: false, msg: "账号不存在" });

  if (pathname === "/api/me" && req.method === "GET")
    return json(res, 200, { ok: true, user: me, hasSave: !!obj.save, updatedAt: obj.updatedAt || 0 });
  if (pathname === "/api/save" && req.method === "GET") {
    if (!obj.save) return json(res, 200, { ok: false, msg: "云端还没有存档" });
    return json(res, 200, { ok: true, save: obj.save, updatedAt: obj.updatedAt || 0 });
  }
  if (pathname === "/api/save" && req.method === "PUT") {
    const body = await readBody(req);
    const s = body.save;
    if (!saveShapeOk(s)) return json(res, 200, { ok: false, msg: "存档格式不正确" });
    if (JSON.stringify(s).length > MAX_SAVE) return json(res, 200, { ok: false, msg: "存档过大（>200KB）" });
    const cloudAt = obj.updatedAt || 0;
    const base = typeof body.base === "number" ? body.base : 0;
    if (!body.force && cloudAt && base !== cloudAt)
      return json(res, 200, { ok: false, conflict: true, updatedAt: cloudAt, msg: "云端存档比你的本地副本更新" });
    obj.save = s;
    obj.updatedAt = Date.now();
    writeUser(me, obj);
    return json(res, 200, { ok: true, updatedAt: obj.updatedAt });
  }
  if (pathname === "/api/pass" && req.method === "POST") {
    const { old: oldP, new: newP } = await readBody(req);
    if (!verifyPass(String(oldP || ""), obj.salt, obj.hash)) return json(res, 200, { ok: false, msg: "原密码错误" });
    if (typeof newP !== "string" || newP.length < 6 || newP.length > 64) return json(res, 200, { ok: false, msg: "新密码需 6-64 位" });
    obj.salt = crypto.randomBytes(16).toString("hex");
    obj.hash = hashPass(newP, obj.salt);
    const h = (req.headers.authorization || "").match(/^Bearer ([0-9a-f]{48})$/);
    obj.tokens = h ? { [h[1]]: Date.now() } : {};
    writeUser(me, obj);
    auth.map.clear();
    return json(res, 200, { ok: true });
  }
  if (pathname === "/api/delete" && req.method === "POST") {
    const { pass } = await readBody(req);
    if (!verifyPass(String(pass || ""), obj.salt, obj.hash)) return json(res, 200, { ok: false, msg: "密码错误" });
    Object.keys(obj.tokens || {}).forEach((t) => auth.map.delete(t));
    fs.unlinkSync(safeName(me));
    return json(res, 200, { ok: true });
  }
  if (pathname === "/api/logout" && req.method === "POST") {
    const h = (req.headers.authorization || "").match(/^Bearer ([0-9a-f]{48})$/);
    if (h && obj.tokens) { delete obj.tokens[h[1]]; writeUser(me, obj); auth.map.delete(h[1]); }
    return json(res, 200, { ok: true });
  }
  return json(res, 404, { ok: false, msg: "not found" });
}

/* ---------- 静态托管 ---------- */
const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8",
  ".png": "image/png", ".svg": "image/svg+xml", ".ico": "image/x-icon",
  ".webmanifest": "application/manifest+json"
};
function serveStatic(req, res, pathname) {
  if (pathname === "/") pathname = "/index.html";
  let file;
  try { file = path.join(ROOT, decodeURIComponent(pathname)); } catch (e) { return json(res, 400, { ok: false }); }
  const rel = path.relative(ROOT, file);
  if (rel.startsWith("..") || path.isAbsolute(rel) || rel.split(path.sep)[0] === "server-data") {
    return json(res, 403, { ok: false, msg: "forbidden" });
  }
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
      return res.end("404 Not Found");
    }
    res.writeHead(200, { "Content-Type": MIME[path.extname(file).toLowerCase()] || "application/octet-stream", "Cache-Control": "no-cache" });
    fs.createReadStream(file).pipe(res);
  });
}

const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, "http://x").pathname;
  if (pathname.startsWith("/api/")) {
    handleApi(req, res, pathname).catch((e) => json(res, 400, { ok: false, msg: "请求无效：" + e.message }));
    return;
  }
  if (req.method !== "GET" && req.method !== "HEAD") return json(res, 405, { ok: false });
  serveStatic(req, res, pathname);
});
server.listen(PORT, () => {
  console.log(`化学实验室：元素纪元 —— 服务器已启动`);
  console.log(`  本机访问: http://localhost:${PORT}`);
  console.log(`  手机同局域网访问: http://<本机IP>:${PORT}`);
  console.log(`  账号/云存档数据目录: ${DATA_DIR}`);
});
