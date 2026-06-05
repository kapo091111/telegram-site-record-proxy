import express from 'express';
import { dateFolderName, hkDate, normaliseDate } from './date.js';
export function configureAdminApp(input) {
    input.app.get('/app', (_req, res) => {
        res.type('html').send(adminHtml());
    });
    const router = express.Router();
    router.use(express.json({ limit: '1mb' }));
    router.use((req, res, next) => {
        if (!input.adminPin) {
            res.status(503).json({ error: '未設定 WEB_ADMIN_PIN。請先在 Render Environment 加入。' });
            return;
        }
        const pin = String(req.header('x-admin-pin') || req.query.pin || req.body?.pin || '');
        if (pin !== input.adminPin) {
            res.status(401).json({ error: 'PIN 錯誤。' });
            return;
        }
        next();
    });
    router.get('/state', async (_req, res) => {
        await sendState(res, input);
    });
    router.post('/site', async (req, res) => {
        const siteId = String(req.body?.siteId || '');
        const previousSite = await input.sites.currentSite(input.ownerUserId);
        const site = await input.db.siteById(input.ownerUserId, siteId);
        if (!site || site.archivedAt) {
            res.status(404).json({ error: '搵唔到地盤。' });
            return;
        }
        await input.db.setCurrentSite(input.ownerUserId, site.id);
        if (previousSite?.id !== site.id) {
            await input.db.setFileRemark(input.ownerUserId, null);
        }
        await sendState(res, input);
    });
    router.post('/date', async (req, res) => {
        const date = normaliseDate(String(req.body?.date || ''));
        if (!date) {
            res.status(400).json({ error: '日期格式錯，請用 YYYYMMDD 或 YYYY-MM-DD。' });
            return;
        }
        await input.db.setRecordDate(input.ownerUserId, date);
        await sendState(res, input);
    });
    router.post('/remark', async (req, res) => {
        const raw = req.body?.remark;
        const remark = raw === null ? null : String(raw || '').trim() || null;
        await input.db.setFileRemark(input.ownerUserId, remark);
        await sendState(res, input);
    });
    router.post('/sync-sites', async (_req, res) => {
        const result = await input.sites.syncSitesFromSheet(input.ownerUserId);
        res.json({ ok: true, result });
    });
    router.post('/sync-pending', async (_req, res) => {
        if (!input.sync) {
            res.status(503).json({ error: '未設定 Synology SFTP。' });
            return;
        }
        const result = await input.sync.syncPendingDates();
        const uploaded = result.reduce((sum, item) => sum + item.uploaded, 0);
        res.json({ ok: true, siteDates: result.length, uploaded });
    });
    input.app.use('/api/admin', router);
}
async function sendState(res, input) {
    const [currentSite, recordDate, remark, sites] = await Promise.all([
        input.sites.currentSite(input.ownerUserId),
        currentRecordDate(input.db, input.ownerUserId),
        input.db.currentFileRemark(input.ownerUserId),
        input.db.listSites(input.ownerUserId, 100)
    ]);
    const counts = currentSite ? await input.db.syncCountsForDate(currentSite.id, recordDate) : { total: 0, synced: 0, pending: 0 };
    res.json({
        currentSite,
        recordDate,
        remark,
        folderName: dateFolderName(recordDate, remark || ''),
        counts,
        sites,
        syncEnabled: Boolean(input.sync)
    });
}
async function currentRecordDate(db, userId) {
    return (await db.currentRecordDate(userId)) || hkDate();
}
function adminHtml() {
    return `<!doctype html>
<html lang="zh-Hant">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>工程現場記錄</title>
  <style>
    :root { color-scheme: light; --bg:#f6f7f4; --panel:#fff; --text:#1f2a24; --muted:#6b766f; --line:#dfe5dc; --green:#2f8f5b; --green2:#dff2e7; --danger:#b42318; }
    * { box-sizing: border-box; }
    body { margin:0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans TC", Arial, sans-serif; background:var(--bg); color:var(--text); }
    header { position:sticky; top:0; z-index:2; background:rgba(246,247,244,.94); backdrop-filter: blur(10px); border-bottom:1px solid var(--line); padding:14px 16px; }
    h1 { margin:0; font-size:20px; letter-spacing:0; }
    main { max-width:880px; margin:0 auto; padding:16px; display:grid; gap:14px; }
    section { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:14px; }
    h2 { margin:0 0 12px; font-size:16px; }
    .status-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
    .metric { border:1px solid var(--line); border-radius:8px; padding:10px; min-height:72px; }
    .metric span { display:block; color:var(--muted); font-size:12px; margin-bottom:6px; }
    .metric strong { font-size:18px; overflow-wrap:anywhere; }
    .row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
    input, select, button { font:inherit; min-height:42px; border-radius:8px; border:1px solid var(--line); background:#fff; color:var(--text); }
    input, select { padding:0 10px; flex:1; min-width:0; }
    button { padding:0 14px; cursor:pointer; background:#fff; }
    button.primary { background:var(--green); border-color:var(--green); color:#fff; }
    button.soft { background:var(--green2); border-color:var(--green2); color:#155f39; }
    button.danger { color:var(--danger); }
    .remarks { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
    .sites { display:grid; gap:8px; max-height:360px; overflow:auto; }
    .site { text-align:left; justify-content:flex-start; min-height:48px; overflow-wrap:anywhere; }
    .site.active { background:var(--green); color:#fff; border-color:var(--green); }
    .message { min-height:22px; color:var(--muted); font-size:14px; }
    .hidden { display:none; }
    @media (max-width: 620px) { main { padding:12px; } .status-grid { grid-template-columns:1fr; } .remarks { grid-template-columns:1fr 1fr; } }
  </style>
</head>
<body>
  <header><h1>工程現場記錄</h1></header>
  <main>
    <section id="login">
      <h2>登入</h2>
      <div class="row"><input id="pin" type="password" inputmode="numeric" placeholder="輸入管理 PIN"><button class="primary" id="savePin">登入</button></div>
      <p class="message">PIN 只會保存在呢部裝置。</p>
    </section>

    <section id="dashboard" class="hidden">
      <h2>目前設定</h2>
      <div class="status-grid">
        <div class="metric"><span>地盤</span><strong id="currentSite">未選擇</strong></div>
        <div class="metric"><span>資料夾</span><strong id="folderName">-</strong></div>
        <div class="metric"><span>今日檔案</span><strong id="fileCounts">0 / 0</strong></div>
        <div class="metric"><span>同步</span><strong id="syncState">-</strong></div>
      </div>
    </section>

    <section id="settings" class="hidden">
      <h2>上傳設定</h2>
      <div class="row">
        <input id="dateInput" placeholder="YYYYMMDD">
        <button class="soft" data-date="today">今日</button>
        <button class="soft" data-date="yesterday">昨日</button>
      </div>
      <h2 style="margin-top:14px;">檔案備注</h2>
      <div class="remarks">
        <button class="soft" data-remark="打拆">打拆</button>
        <button class="soft" data-remark="水電完成">水電完成</button>
        <button class="soft" data-remark="泥水完成">泥水完成</button>
        <button class="danger" data-remark="">清除</button>
      </div>
    </section>

    <section id="sitePanel" class="hidden">
      <h2>地盤</h2>
      <div class="row"><input id="siteSearch" placeholder="搜尋 25026 / 海怡 / 2401"><button id="syncSites">同步 Sheet</button></div>
      <div id="siteList" class="sites"></div>
    </section>

    <section id="actions" class="hidden">
      <h2>同步</h2>
      <div class="row"><button id="syncPending" class="primary">補傳未同步</button><button id="refresh">重新整理</button></div>
      <p id="message" class="message"></p>
    </section>
  </main>
  <script>
    const $ = (id) => document.getElementById(id);
    const pinKey = 'siteRecordAdminPin';
    let state = null;

    function pin() { return localStorage.getItem(pinKey) || $('pin').value.trim(); }
    async function api(path, options = {}) {
      const res = await fetch('/api/admin' + path, {
        ...options,
        headers: { 'content-type': 'application/json', 'x-admin-pin': pin(), ...(options.headers || {}) }
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || '操作失敗');
      return data;
    }
    function showApp() {
      ['dashboard','settings','sitePanel','actions'].forEach(id => $(id).classList.remove('hidden'));
      $('login').classList.add('hidden');
    }
    async function load() {
      try {
        state = await api('/state');
        showApp();
        render();
      } catch (error) {
        $('login').classList.remove('hidden');
        $('message').textContent = error.message;
      }
    }
    function render() {
      $('currentSite').textContent = state.currentSite?.name || '未選擇';
      $('folderName').textContent = state.folderName || '-';
      $('fileCounts').textContent = state.counts.total + ' 個，待補傳 ' + state.counts.pending;
      $('syncState').textContent = state.syncEnabled ? (state.counts.pending ? '有待補傳' : '正常') : '未設定 SFTP';
      $('dateInput').value = state.recordDate.replaceAll('-', '');
      renderSites();
    }
    function renderSites() {
      const q = $('siteSearch').value.trim().toLowerCase();
      const sites = state.sites.filter(site => !q || site.name.toLowerCase().includes(q) || String(site.siteCode || '').includes(q));
      $('siteList').innerHTML = sites.map(site => '<button class="site ' + (state.currentSite?.id === site.id ? 'active' : '') + '" data-site="' + site.id + '">' + site.name + '</button>').join('');
      document.querySelectorAll('[data-site]').forEach(btn => btn.onclick = async () => {
        state = await api('/site', { method:'POST', body: JSON.stringify({ siteId: btn.dataset.site }) });
        render();
      });
    }
    function hkOffset(days) {
      const d = new Date(new Date().toLocaleString('en-US', { timeZone:'Asia/Hong_Kong' }));
      d.setDate(d.getDate() + days);
      return d.getFullYear() + String(d.getMonth()+1).padStart(2,'0') + String(d.getDate()).padStart(2,'0');
    }
    $('savePin').onclick = async () => { localStorage.setItem(pinKey, $('pin').value.trim()); await load(); };
    $('siteSearch').oninput = renderSites;
    $('refresh').onclick = load;
    $('syncSites').onclick = async () => { $('message').textContent = '同步中...'; const r = await api('/sync-sites', { method:'POST', body:'{}' }); $('message').textContent = '已同步：使用中 ' + r.result.active + '，已完成 ' + r.result.archived; await load(); };
    $('syncPending').onclick = async () => { $('message').textContent = '補傳中...'; const r = await api('/sync-pending', { method:'POST', body:'{}' }); $('message').textContent = '已補傳 ' + r.uploaded + ' 個檔案'; await load(); };
    document.querySelectorAll('[data-remark]').forEach(btn => btn.onclick = async () => { state = await api('/remark', { method:'POST', body: JSON.stringify({ remark: btn.dataset.remark || null }) }); render(); });
    document.querySelectorAll('[data-date]').forEach(btn => btn.onclick = async () => { const value = btn.dataset.date === 'today' ? hkOffset(0) : hkOffset(-1); state = await api('/date', { method:'POST', body: JSON.stringify({ date: value }) }); render(); });
    $('dateInput').onchange = async () => { state = await api('/date', { method:'POST', body: JSON.stringify({ date: $('dateInput').value }) }); render(); };
    if (localStorage.getItem(pinKey)) load();
  </script>
</body>
</html>`;
}
