'use strict';
/*
 * 成绩轨迹 · 后端服务
 * 零第三方依赖，Node.js 18+ 直接运行：node server.js
 *
 * 数据文件：DATA_DIR/grades.json   { rev, data }
 * 认证文件：DATA_DIR/auth.json     { salt, hash, passwordChanged, tokens }
 *
 * 前端（资料库页面）写死的接口契约：
 *   GET  api/health     -> 200 { passwordChanged }
 *   POST api/login      -> 200 { token } | 401 { error }
 *   GET  api/state      -> 200 { rev, data } | 401
 *   PUT  api/state      -> 200 { rev } | 401 | 409（rev 冲突）
 *   POST api/password   -> 200 { token } | 401 | 400
 * 试卷附件（v1.2 新增）：
 *   GET    api/attachments?examId=&key=  -> 200 { items }
 *   POST   api/attachments               -> 上传（JSON + base64）
 *   DELETE api/attachments?id=|examId=   -> 删除单个 / 某次考试全部
 *   GET    api/file?id=                 -> 附件二进制流（inline 预览）
 * 附件不参与 grades.json 的 rev，避免多设备写成绩时被附件操作干扰。
 * 所有需要鉴权的请求带请求头 X-Access-Key: <token>
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const PUBLIC_DIR = process.env.PUBLIC_DIR || path.join(__dirname, 'public');
const STATE_FILE = path.join(DATA_DIR, 'grades.json');
const AUTH_FILE = path.join(DATA_DIR, 'auth.json');
const DEFAULT_PASSWORD = process.env.DEFAULT_PASSWORD || 'meimima';
const MAX_BODY = 12 * 1024 * 1024;            // 笔记本可插图（压缩后 base64），放宽到 12MB 并挡住异常请求
const ATTACH_DIR = path.join(DATA_DIR, 'attachments');
const ATTACH_INDEX = path.join(DATA_DIR, 'attachments.json');
const MAX_FILE = 20 * 1024 * 1024;            // 单个附件原始大小上限 20MB
const MAX_UPLOAD_BODY = 30 * 1024 * 1024;     // base64 膨胀约 1/3，JSON 请求体放宽到 30MB
const ALLOWED_MIME = {
  'application/pdf': '.pdf',
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
  'image/gif': '.gif',
  'image/heic': '.heic',
  'image/heif': '.heif'
};

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(ATTACH_DIR, { recursive: true });

/* ---------------- 文件读写（原子替换，避免写一半断电损坏） ---------------- */
function readJSON(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (e) { return null; }
}
function writeJSON(file, value) {
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(value, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, file);
}

/* ---------------- 成绩数据 ---------------- */
let store = readJSON(STATE_FILE);
if (!store || typeof store !== 'object' || typeof store.rev !== 'number') {
  // data 为 null 时，前端会用内置的默认科目模板初始化，不要在这里塞空 subjects
  store = { rev: 0, data: null };
  writeJSON(STATE_FILE, store);
  console.log('[init] 已创建 ' + STATE_FILE);
}
function saveState(next) {
  writeJSON(STATE_FILE, next);
  store = next;
  console.log('[save] rev=' + store.rev + ' -> ' + STATE_FILE);
}

/* ---------------- 试卷附件 ---------------- */
let attachIndex = readJSON(ATTACH_INDEX);
if (!attachIndex || !Array.isArray(attachIndex.items)) {
  attachIndex = { items: [] };
  writeJSON(ATTACH_INDEX, attachIndex);
}
function saveAttachIndex() { writeJSON(ATTACH_INDEX, attachIndex); }
function safeFileName(n) {
  return String(n || '附件').replace(/[\\/:*?"<>|\r\n\t]/g, '_').slice(0, 120);
}
function extOf(mime, name) {
  if (ALLOWED_MIME[mime]) return ALLOWED_MIME[mime];
  const m = String(name || '').toLowerCase().match(/\.(pdf|jpe?g|png|webp|gif|heic|heif)$/);
  if (!m) return '';
  return '.' + (m[1] === 'jpeg' ? 'jpg' : m[1]);
}
function mimeOf(ext, fallback) {
  if (ALLOWED_MIME[fallback]) return fallback;
  const hit = Object.keys(ALLOWED_MIME).filter(function (k) { return ALLOWED_MIME[k] === ext; })[0];
  return hit || 'application/octet-stream';
}
/* 校验文件头，避免把伪装成 PDF / 图片的 HTML 以 inline 方式返回 */
function magicOK(ext, buf) {
  if (ext === '.pdf') return buf.slice(0, 4).toString('latin1') === '%PDF';
  if (ext === '.jpg') return buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff;
  if (ext === '.png') return buf.slice(0, 4).toString('hex') === '89504e47';
  if (ext === '.webp') return buf.slice(0, 4).toString('latin1') === 'RIFF' && buf.slice(8, 12).toString('latin1') === 'WEBP';
  if (ext === '.gif') return buf.slice(0, 4).toString('latin1').indexOf('GIF8') === 0;
  if (ext === '.heic' || ext === '.heif') return buf.slice(4, 8).toString('latin1') === 'ftyp';
  return false;
}
function publicItem(it) {
  return { id: it.id, examId: it.examId, key: it.key, name: it.name,
    mime: it.mime, size: it.size, createdAt: it.createdAt };
}
function createAttachment(body) {
  const examId = String(body.examId || '').slice(0, 80);
  const key = String(body.key || '').slice(0, 80);
  if (!examId || !key) throw new Error('缺少考试或科目信息');
  const name = safeFileName(body.name);
  const ext = extOf(String(body.mime || ''), name);
  if (!ext) throw new Error('仅支持 PDF、JPG、PNG、WEBP、GIF、HEIC 格式的试卷');
  let raw = String(body.data || '');
  const comma = raw.indexOf(',');
  if (raw.slice(0, 5) === 'data:' && comma > 0) raw = raw.slice(comma + 1); // 兼容直接传 dataURL
  const buf = Buffer.from(raw, 'base64');
  if (!buf.length) throw new Error('文件内容为空');
  if (buf.length > MAX_FILE) throw new Error('单个附件不能超过 20MB');
  if (!magicOK(ext, buf)) throw new Error('文件内容与格式不符，请确认是真实的 PDF 或图片');
  const id = crypto.randomBytes(12).toString('hex');
  fs.writeFileSync(path.join(ATTACH_DIR, id + ext), buf, { mode: 0o600 });
  const item = { id: id, examId: examId, key: key, name: name,
    mime: mimeOf(ext, String(body.mime || '')), size: buf.length, ext: ext,
    createdAt: new Date().toISOString() };
  attachIndex.items.push(item);
  saveAttachIndex();
  console.log('[attach] + ' + name + ' (' + buf.length + ' bytes) exam=' + examId + '/' + key);
  return publicItem(item);
}
function removeAttachments(pred) {
  const keep = [], gone = [];
  attachIndex.items.forEach(function (it) {
    if (pred(it)) gone.push(it); else keep.push(it);
  });
  gone.forEach(function (it) {
    try { fs.unlinkSync(path.join(ATTACH_DIR, it.id + it.ext)); } catch (e) { /* 文件可能已被手动删除 */ }
  });
  if (gone.length) { attachIndex.items = keep; saveAttachIndex(); }
  console.log('[attach] - ' + gone.length + ' 个附件');
  return gone.length;
}

/* ---------------- 认证 ---------------- */
function hashPassword(salt, password) {
  return crypto.scryptSync(String(password), salt, 64).toString('hex');
}
function newSalt() { return crypto.randomBytes(16).toString('hex'); }
function newToken() { return crypto.randomBytes(32).toString('hex'); }

let auth = readJSON(AUTH_FILE);
if (!auth || typeof auth.salt !== 'string' || typeof auth.hash !== 'string' || !Array.isArray(auth.tokens)) {
  const salt = newSalt();
  auth = {
    salt: salt,
    hash: hashPassword(salt, DEFAULT_PASSWORD),
    passwordChanged: false,
    tokens: []
  };
  writeJSON(AUTH_FILE, auth);
  console.log('[init] 已创建 ' + AUTH_FILE + '（首次使用默认密码，请登录后立刻修改）');
}
function verifyPassword(pwd) {
  if (typeof pwd !== 'string') return false;
  let got;
  try { got = Buffer.from(hashPassword(auth.salt, pwd), 'hex'); } catch (e) { return false; }
  const want = Buffer.from(auth.hash, 'hex');
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}
function isAuthorized(req) {
  const key = req.headers['x-access-key'];
  return typeof key === 'string' && key.length > 0 && auth.tokens.indexOf(key) >= 0;
}
function issueToken(reset) {
  const token = newToken();
  auth.tokens = reset ? [token] : auth.tokens.concat([token]).slice(-20);
  writeJSON(AUTH_FILE, auth);
  return token;
}

/* ---------------- HTTP 工具 ---------------- */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.heic': 'image/heic',
  '.pdf': 'application/pdf',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.apk': 'application/vnd.android.package-archive'
};
function sendJSON(res, code, obj) {
  const body = Buffer.from(JSON.stringify(obj));
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  res.end(body);
}
function serveStatic(res, pathname) {
  let rel;
  try { rel = decodeURIComponent(pathname); } catch (e) { return sendJSON(res, 400, { error: 'bad path' }); }
  if (rel === '/' || rel === '') rel = '/index.html';

  const root = path.resolve(PUBLIC_DIR);
  const full = path.resolve(root, '.' + rel);
  if (full !== root && full.indexOf(root + path.sep) !== 0) return sendJSON(res, 403, { error: 'forbidden' });

  fs.stat(full, function (err, st) {
    if (err || !st.isFile()) return sendJSON(res, 404, { error: 'not found' });
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(full).toLowerCase()] || 'application/octet-stream',
      'Content-Length': st.size,
      'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff'
    });
    fs.createReadStream(full).pipe(res);
  });
}
function readBody(req, max) {
  return new Promise(function (resolve, reject) {
    let size = 0;
    const limit = max || MAX_BODY;
    const chunks = [];
    req.on('data', function (c) {
      size += c.length;
      if (size > limit) { reject(new Error('too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', function () {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch (e) { reject(new Error('bad json')); }
    });
    req.on('error', reject);
  });
}
async function bodyOf(req, res, max) {
  try { return await readBody(req, max); }
  catch (e) { sendJSON(res, 400, { error: '请求体不是合法 JSON 或超过大小限制' }); return null; }
}

/* ---------------- 路由 ---------------- */
const server = http.createServer(async function (req, res) {
  let pathname;
  try { pathname = new URL(req.url, 'http://localhost').pathname; }
  catch (e) { return sendJSON(res, 400, { error: 'bad request' }); }

  res.setHeader('X-Frame-Options', 'SAMEORIGIN');
  res.setHeader('Referrer-Policy', 'no-referrer');

  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }

  // 取路径末段的 /api/xxx，兼容挂在反向代理子路径下（如 /grades/api/state）
  const m = pathname.match(/\/api\/([a-z]+)\/?$/i);
  const apiName = m ? m[1].toLowerCase() : null;

  if (apiName === 'health') {
    return sendJSON(res, 200, { ok: true, passwordChanged: auth.passwordChanged === true });
  }

  if (apiName === 'login') {
    if (req.method !== 'POST') return sendJSON(res, 405, { error: 'method not allowed' });
    const body = await bodyOf(req, res); if (!body) return;
    if (!verifyPassword(body.password)) return sendJSON(res, 401, { error: '密码不正确' });
    return sendJSON(res, 200, { token: issueToken(false) });
  }

  if (apiName === 'state') {
    if (!isAuthorized(req)) return sendJSON(res, 401, { error: '需要密码' });

    if (req.method === 'GET') {
      return sendJSON(res, 200, { rev: store.rev, data: store.data });
    }
    if (req.method === 'PUT') {
      const body = await bodyOf(req, res); if (!body) return;
      if (typeof body.rev !== 'number' || body.rev !== store.rev) {
        // 另一台设备已经改过数据：让前端提示刷新，绝不静默覆盖
        return sendJSON(res, 409, { error: '数据已在其它设备更新', rev: store.rev });
      }
      if (!Object.prototype.hasOwnProperty.call(body, 'data')) {
        return sendJSON(res, 400, { error: '缺少 data' });
      }
      const nextRev = store.rev + 1;
      saveState({ rev: nextRev, data: body.data });
      return sendJSON(res, 200, { rev: nextRev });
    }
    return sendJSON(res, 405, { error: 'method not allowed' });
  }

  if (apiName === 'password') {
    if (req.method !== 'POST') return sendJSON(res, 405, { error: 'method not allowed' });
    if (!isAuthorized(req)) return sendJSON(res, 401, { error: '请先登录' });
    const body = await bodyOf(req, res); if (!body) return;
    if (!verifyPassword(body.oldPassword)) return sendJSON(res, 401, { error: '当前密码不正确' });
    const nw = body.newPassword;
    if (typeof nw !== 'string' || nw.length < 4) return sendJSON(res, 400, { error: '新密码至少 4 位' });
    auth.salt = newSalt();
    auth.hash = hashPassword(auth.salt, nw);
    auth.passwordChanged = true;
    const token = issueToken(true); // 清空其它设备的登录态
    console.log('[auth] 密码已修改，其它设备需重新登录');
    return sendJSON(res, 200, { token: token });
  }

  /* ---------- 试卷附件 ---------- */
  if (apiName === 'attachments') {
    if (!isAuthorized(req)) return sendJSON(res, 401, { error: '需要密码' });
    const q = new URL(req.url, 'http://localhost').searchParams;

    if (req.method === 'GET') {
      const examId = q.get('examId') || '';
      const key = q.get('key');
      const items = attachIndex.items.filter(function (it) {
        return (!examId || it.examId === examId) && (key == null || it.key === key);
      }).map(publicItem);
      return sendJSON(res, 200, { items: items });
    }

    if (req.method === 'POST') {
      const upload = await bodyOf(req, res, MAX_UPLOAD_BODY); if (!upload) return;
      try {
        return sendJSON(res, 200, { item: createAttachment(upload) });
      } catch (e) {
        return sendJSON(res, 400, { error: e.message || '上传失败' });
      }
    }

    if (req.method === 'DELETE') {
      const id = q.get('id'), examId = q.get('examId') || '', key = q.get('key');
      const all = q.get('all') === '1';
      if (!id && !examId && !all) return sendJSON(res, 400, { error: '缺少删除条件' });
      const n = removeAttachments(function (it) {
        if (all) return true;
        if (id) return it.id === id;
        return it.examId === examId && (key == null || it.key === key);
      });
      return sendJSON(res, 200, { deleted: n });
    }
    return sendJSON(res, 405, { error: 'method not allowed' });
  }

  if (apiName === 'file') {
    if (!isAuthorized(req)) return sendJSON(res, 401, { error: '需要密码' });
    if (req.method !== 'GET' && req.method !== 'HEAD') return sendJSON(res, 405, { error: 'method not allowed' });
    const fid = new URL(req.url, 'http://localhost').searchParams.get('id') || '';
    const it = attachIndex.items.filter(function (x) { return x.id === fid; })[0];
    if (!it) return sendJSON(res, 404, { error: '附件不存在' });
    const fp = path.join(ATTACH_DIR, it.id + it.ext);
    let st;
    try { st = fs.statSync(fp); } catch (e) { return sendJSON(res, 404, { error: '附件文件已丢失' }); }
    res.writeHead(200, {
      'Content-Type': it.mime,
      'Content-Length': st.size,
      'Content-Disposition': "inline; filename*=UTF-8''" + encodeURIComponent(it.name),
      'Cache-Control': 'private, max-age=300',
      'X-Content-Type-Options': 'nosniff'
    });
    if (req.method === 'HEAD') return res.end();
    return fs.createReadStream(fp).pipe(res);
  }

  if (apiName) return sendJSON(res, 404, { error: 'unknown api' });
  if (req.method !== 'GET' && req.method !== 'HEAD') return sendJSON(res, 405, { error: 'method not allowed' });
  return serveStatic(res, pathname);
});

server.listen(PORT, HOST, function () {
  console.log('成绩轨迹已启动: http://' + (HOST === '0.0.0.0' ? 'localhost' : HOST) + ':' + PORT);
  console.log('数据目录: ' + path.resolve(DATA_DIR));
  console.log('页面目录: ' + path.resolve(PUBLIC_DIR));
  console.log('默认密码状态: ' + (auth.passwordChanged ? '已修改' : '仍是默认密码 meimima，请尽快修改'));
});

function shutdown() {
  server.close(function () { process.exit(0); });
  setTimeout(function () { process.exit(0); }, 3000).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
