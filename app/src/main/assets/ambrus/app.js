/* Ambrus — Web Copier & Analyzer · UI utama (WebView)
 *
 * KONTRAK BRIDGE (Kotlin <-> JS)
 *  JS -> Native (addJavascriptInterface(obj, "Android")), semua argumen string JSON:
 *    Android.startScan(json)   {projectId,url,level,authorized,maxResources,timeout}  // scan cepat
 *    Android.startPreview(json){projectId,url,level,authorized}  // preview + rekam sesi navigasi
 *    Android.stopScan(projectId)
 *    Android.saveZip(base64, fileName)     // simpan ZIP ke penyimpanan
 *    Android.openUrl(url)                  // buka di browser eksternal
 *  Native -> JS (webView.evaluateJavascript("Ambrus.emit(<json>)")):
 *    {type:'redirect', from, to}
 *    {type:'resource', url, mime, size, status, page, hash, path, error, content}  // content: teks opsional
 *    {type:'request',  url, method, status, mime, page, initiator, ws}
 *    {type:'server',   host, status, headers:{}, tls:{}, ip, ttfb}
 *    {type:'dns',      host, records:[]}
 *    {type:'progress', value:0..100, message}
 *    {type:'done'} | {type:'error', message}
 *  Tanpa bridge, UI berjalan dalam mode DEMO (data simulasi, ditandai jelas).
 */
(function () {
  'use strict';
  const $ = (s, e = document) => e.querySelector(s);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const bridge = window.Android || null;
  const KEY = 'ambrus.v2';
  const TOOL_VERSION = '2.0.0-ui';

  /* ---------- state ---------- */
  let S = load();
  function load() {
    try { const d = JSON.parse(localStorage.getItem(KEY)); if (d && d.projects) return d; } catch (e) {}
    return { projects: [], currentId: null, settings: { maxResources: 500, timeout: 30, mask: true }, lastLog: [] };
  }
  function save() { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) { toast('Penyimpanan penuh'); } }
  const cur = () => S.projects.find(p => p.id === S.currentId) || null;
  const uid = () => 'p_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  let log = []; let scanning = false; let progress = 0; let timers = [];

  /* ---------- util ---------- */
  function toast(m) { const t = $('#toast'); t.textContent = m; t.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 2200); }
  const fmtSize = n => n == null ? '–' : n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(2) + ' MB';
  const fmtTime = t => new Date(t).toLocaleString('id-ID');
  const SENS = /(token|key|secret|session|sess|auth|password|passwd|pwd|sid|jwt|signature|sig|cookie)/i;
  function mask(u) {
    if (!S.settings.mask) return u;
    try {
      const x = new URL(u);
      x.searchParams.forEach((v, k) => { if (SENS.test(k) || v.length > 40) x.searchParams.set(k, '***'); });
      if (x.username || x.password) { x.username = '***'; x.password = ''; }
      return x.toString();
    } catch (e) { return u; }
  }
  function maskHeaders(h) { const o = {}; for (const k in (h || {})) o[k] = /^(set-cookie|cookie|authorization|x-api-key|proxy-authorization)$/i.test(k) ? '***' : h[k]; return o; }
  function isPrivateHost(h) {
    h = h.toLowerCase();
    if (h === 'localhost' || h.endsWith('.local') || h.endsWith('.internal') || h === '[::1]') return true;
    const m = h.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
    if (m) { const [a, b] = [+m[1], +m[2]]; return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127); }
    return false;
  }
  function validateUrl(raw) {
    let u; try { u = new URL(raw.trim()); } catch (e) { return { ok: false, msg: 'Format URL tidak valid' }; }
    if (u.protocol !== 'https:') return { ok: false, msg: 'Hanya HTTPS yang diizinkan' };
    if (isPrivateHost(u.hostname)) return { ok: false, msg: 'Alamat lokal/internal diblokir' };
    return { ok: true, url: u };
  }
  function safePath(p) { return String(p).replace(/\\/g, '/').split('/').filter(s => s && s !== '.' && s !== '..').join('/'); }
  function classifyResource(r) {
    const m = (r.mime || '').toLowerCase(), u = (r.url || '').toLowerCase().split('?')[0];
    if (/manifest/.test(u) && /json/.test(u)) return 'Manifest';
    if (/(^|\/)sw\.js$|service-?worker/.test(u)) return 'Service Worker';
    if (/\.map$/.test(u)) return 'Source Map';
    if (m.includes('html') || /\.html?$/.test(u)) return 'HTML';
    if (m.includes('css') || /\.css$/.test(u)) return 'CSS';
    if (m.includes('javascript') || /\.m?js$/.test(u)) return 'JS';
    if (m.includes('json') || /\.json$/.test(u)) return 'JSON';
    if (m.includes('xml')) return 'XML';
    if (m.includes('font') || /\.(woff2?|ttf|otf)$/.test(u)) return 'Font';
    if (/\.ico$/.test(u)) return 'Ikon';
    if (m.startsWith('image') || /\.(png|jpe?g|gif|webp|svg|avif)$/.test(u)) return 'Gambar';
    if (m.startsWith('audio') || /\.(mp3|ogg|wav|m4a)$/.test(u)) return 'Audio';
    if (m.startsWith('video') || /\.(mp4|webm)$/.test(u)) return 'Video';
    return 'Lainnya';
  }
  const EP_RULES = [
    [/^wss?:/, 'WebSocket'], [/(login|logout|auth|oauth|token|register|signin|signup)/i, 'Authentication'],
    [/(pay|wallet|deposit|withdraw|billing|checkout)/i, 'Payment'], [/(analytic|telemetry|track|metrics|collect)/i, 'Analytics'],
    [/(leaderboard|game|match|room|inventory|progress|spin|round)/i, 'Game'], [/(user|profile|me\b|account|member)/i, 'User/Profile'],
    [/(config|setting|version|env)/i, 'Configuration'], [/(upload)/i, 'Upload'], [/(download|export)/i, 'Download'],
    [/(content|article|news|post|banner|promo|page)/i, 'Content'], [/(public|health|status|ping)/i, 'Public']
  ];
  function classifyEndpoint(url) { for (const [re, c] of EP_RULES) if (re.test(url)) return c; return 'Unknown'; }
  function gameCat(url) {
    const t = [[/leaderboard|rank/i, 'Leaderboard'], [/inventory|item/i, 'Inventory'], [/progress|level/i, 'Progress'], [/session|token/i, 'Session management'], [/profile|player/i, 'Player profile'], [/config/i, 'Game configuration'], [/state|round|match/i, 'Game state'], [/telemetry|analytic/i, 'Telemetry'], [/wallet|pay/i, 'Wallet/Pembayaran'], [/cdn|content|asset/i, 'Content delivery']];
    for (const [re, c] of t) if (re.test(url)) return c; return null;
  }
  function hostKind(host, base, p) {
    if (host === base) return 'Website';
    if ((p.endpoints || []).some(e => e.host === host)) return 'API candidate';
    if (/cdn|cloudfront|akamai|fastly|jsdelivr|cdnjs|unpkg/.test(host)) return 'CDN';
    if (/static|assets|img|media|files/.test(host)) return 'Asset host';
    if (/analytics|gtag|google-analytics|doubleclick|sentry|hotjar/.test(host)) return 'Analytics';
    if (host.endsWith('.' + base.replace(/^www\./, ''))) return 'Subdomain';
    return 'Eksternal';
  }
  function newProject(url, level, authorized) {
    return { id: uid(), url, finalUrl: url, level, authorized, createdAt: Date.now(), status: 'baru', exportStatus: 'belum', demo: !bridge,
      resources: [], requests: [], endpoints: [], domains: {}, server: {}, redirects: [], dns: [], pages: [url],
      edits: { siteName: '', primary: '#5b8cff', accent: '#7cf0c8', logo: '', favicon: '', apiBase: '', replacements: [], versions: [], draft: null },
      migration: { mode: 'adapter', map: [], newBase: '' }, deploy: { name: '', domain: '', apiDomain: '', d1: '' } };
  }

  /* ---------- ingest events ---------- */
  function emit(ev) {
    if (typeof ev === 'string') { try { ev = JSON.parse(ev); } catch (e) { return; } }
    const p = cur(); if (!p || !ev) return;
    const stamp = Date.now();
    const base = new URL(p.finalUrl || p.url).hostname;
    switch (ev.type) {
      case 'redirect': p.redirects.push({ from: ev.from, to: ev.to }); try { p.finalUrl = ev.to; addDomain(p, new URL(ev.to).hostname, 'redirect dari ' + mask(ev.from)); } catch (e) {} pushLog('↪ ' + mask(ev.from) + ' → ' + mask(ev.to)); break;
      case 'resource': {
        if (p.resources.length >= S.settings.maxResources) { if (!p._capped) { p._capped = true; pushLog('⚠ batas jumlah resource tercapai'); } break; }
        const key = ev.url; if (p.resources.some(r => r.url === key)) break;
        const r = { url: ev.url, mime: ev.mime || '', size: ev.size ?? null, status: ev.status || 'ok', page: ev.page || p.url, hash: ev.hash || null, path: safePath(ev.path || guessPath(ev.url)), error: ev.error || null, content: ev.content, checkedAt: stamp };
        r.category = classifyResource(r);
        if (r.hash && p.resources.some(x => x.hash === r.hash)) r.dup = true;
        p.resources.push(r);
        try { addDomain(p, new URL(ev.url).hostname, 'resource ' + r.category); } catch (e) {}
        pushLog((r.status === 'ok' ? '✓ ' : '✗ ') + r.category + ' ' + mask(ev.url));
        break; }
      case 'request': {
        const q = { url: ev.url, method: ev.method || 'GET', status: ev.status ?? null, mime: ev.mime || '', page: ev.page || p.url, initiator: ev.initiator || '', ws: !!ev.ws || /^wss?:/.test(ev.url), at: stamp };
        p.requests.push(q);
        let u; try { u = new URL(ev.url); } catch (e) { break; }
        addDomain(p, u.hostname, 'permintaan teramati');
        const isApi = q.ws || /json|xml|graphql/.test(q.mime) || /\/(api|v\d|graphql|ws|socket)\b/i.test(u.pathname) || q.method !== 'GET';
        if (isApi) {
          const k = q.method + ' ' + u.host + u.pathname;
          if (!p.endpoints.some(e => e.key === k)) {
            const cat = classifyEndpoint(q.ws ? 'ws:' + u.pathname : u.pathname);
            p.endpoints.push({ key: k, host: u.hostname, path: u.pathname, method: q.method, mime: q.mime, status: q.status, page: q.page, observed: true, schema: false, owner: u.hostname === base || u.hostname.endsWith('.' + base) ? 'milik sendiri (dugaan)' : 'pihak ketiga (dugaan)', category: cat, game: gameCat(u.pathname) });
            pushLog('⇄ ' + q.method + ' ' + u.host + u.pathname + '  [' + cat + ']');
          }
        }
        break; }
      case 'server': p.server[ev.host || base] = { status: ev.status, headers: maskHeaders(ev.headers), tls: ev.tls || null, ip: ev.ip || null, ttfb: ev.ttfb ?? null, at: stamp }; pushLog('ℹ server ' + (ev.host || base) + ' HTTP ' + ev.status); break;
      case 'dns': p.dns.push({ host: ev.host, records: ev.records || [] }); break;
      case 'progress': progress = ev.value || 0; if (ev.message) pushLog(ev.message); break;
      case 'error': scanning = false; p.status = 'gagal'; pushLog('✗ ' + ev.message); toast(ev.message); break;
      case 'done': scanning = false; progress = 100; p.status = 'selesai'; pushLog('✔ pemindaian selesai'); toast('Pemindaian selesai'); break;
    }
    save(); softRender();
  }
  function guessPath(u) { try { const x = new URL(u); return 'assets/' + (x.hostname === new URL(cur().url).hostname ? '' : x.hostname + '/') + (x.pathname.replace(/^\//, '') || 'index'); } catch (e) { return 'assets/file'; } }
  function addDomain(p, host, src) { const d = p.domains[host] || (p.domains[host] = { host, sources: [], count: 0 }); d.count++; if (!d.sources.includes(src) && d.sources.length < 6) d.sources.push(src); }
  function pushLog(m) { log.push(new Date().toLocaleTimeString('id-ID') + ' ' + m); if (log.length > 300) log.shift(); }
  window.Ambrus = { emit };

  /* ---------- scan control ---------- */
  function startScan(url, level, authorized, mode) {
    const v = validateUrl(url); if (!v.ok) return toast(v.msg);
    if (!authorized) return toast('Centang pernyataan izin terlebih dahulu');
    const p = newProject(v.url.toString(), level, authorized); p.status = 'berjalan';
    p.recordMode = mode || 'scan';
    S.projects.unshift(p); S.currentId = p.id; save(); log = []; progress = 0; scanning = true;
    pushLog('▶ mulai: ' + p.url + ' (Level ' + level + ', mode ' + p.recordMode + ')');
    if (v.url.hash) pushLog('ℹ fragmen ' + v.url.hash + ' adalah rute sisi-browser; gunakan Preview & Rekam untuk menavigasi SPA.');
    if (mode === 'preview' && bridge && bridge.startPreview) {
      pushLog('● Preview + rekam: buka situs, navigasi seperti merekam, lalu ketuk Selesai & Copy');
      bridge.startPreview(JSON.stringify({ projectId: p.id, url: p.url, level, authorized }));
      location.hash = '#/browser'; render();
      return;
    }
    if (bridge) { bridge.startScan(JSON.stringify({ projectId: p.id, url: p.url, level, authorized, maxResources: S.settings.maxResources, timeout: S.settings.timeout })); }
    else demoScan(p);
    location.hash = '#/browser'; render();
  }
  function stopScan() { timers.forEach(clearTimeout); timers = []; if (bridge && cur()) bridge.stopScan(cur().id); scanning = false; if (cur()) { cur().status = 'dihentikan'; save(); } pushLog('■ dihentikan'); render(); }
  function demoScan(p) {
    const u = new URL(p.url), h = u.hostname, o = u.origin;
    const evs = [
      { type: 'server', host: h, status: 200, headers: { 'content-type': 'text/html', 'cache-control': 'max-age=300', server: 'contoh', 'set-cookie': 'sid=abc' }, tls: { issuer: '(contoh)', protocol: 'TLS 1.3' }, ttfb: 142 },
      { type: 'resource', url: o + '/', mime: 'text/html', size: 4210, hash: 'h1', path: 'index.html' },
      { type: 'resource', url: o + '/assets/app.js', mime: 'application/javascript', size: 183000, hash: 'h2', path: 'assets/app.js' },
      { type: 'resource', url: o + '/assets/app.css', mime: 'text/css', size: 22000, hash: 'h3', path: 'assets/app.css' },
      { type: 'resource', url: o + '/assets/logo.png', mime: 'image/png', size: 8800, hash: 'h4', path: 'assets/logo.png' },
      { type: 'resource', url: o + '/manifest.json', mime: 'application/json', size: 600, hash: 'h5', path: 'manifest.json' },
      { type: 'resource', url: o + '/sw.js', mime: 'application/javascript', size: 2100, hash: 'h6', path: 'sw.js' },
      { type: 'resource', url: 'https://static.' + h.replace(/^www\./, '') + '/fonts/main.woff2', mime: 'font/woff2', size: 31000, hash: 'h7', path: 'assets/fonts/main.woff2' },
      { type: 'resource', url: 'https://cdn.jsdelivr.net/npm/lib@1/dist/lib.min.js', mime: 'application/javascript', size: 40000, status: 'ok', hash: 'h8' },
      { type: 'resource', url: o + '/assets/missing.png', mime: 'image/png', status: 'gagal', error: 'HTTP 404' },
      { type: 'request', url: o + '/api/config', method: 'GET', status: 200, mime: 'application/json', initiator: 'assets/app.js' },
      { type: 'request', url: o + '/api/user/profile', method: 'GET', status: 401, mime: 'application/json', initiator: 'assets/app.js' },
      { type: 'request', url: o + '/api/game/leaderboard', method: 'GET', status: 200, mime: 'application/json', initiator: 'assets/app.js' },
      { type: 'request', url: 'wss://' + h + '/ws', method: 'GET', ws: true, initiator: 'assets/app.js' },
      { type: 'request', url: 'https://analytics.example-tracker.test/collect?token=SECRET123', method: 'POST', status: 204, mime: '', initiator: 'index.html' },
      { type: 'done' }
    ];
    evs.forEach((e, i) => timers.push(setTimeout(() => { if (!scanning) return; emit(Object.assign({ page: p.url }, e)); emit({ type: 'progress', value: Math.round((i + 1) / evs.length * 100) }); }, 350 * (i + 1))));
  }

  /* ---------- router ---------- */
  const NAV = { home: 'home', browser: 'home', analyze: 'analyze', editor: 'editor', projects: 'projects', more: 'more', migration: 'more', export: 'more', deploy: 'more', history: 'more', settings: 'more' };
  function route() { const h = (location.hash || '#/home').slice(2).split('/'); return { name: h[0] || 'home', sub: h[1] || '' }; }
  function render() {
    const r = route(), v = $('#view');
    const fn = VIEWS[r.name] || VIEWS.home;
    v.innerHTML = fn(r.sub); (BIND[r.name] || (() => { }))(r.sub);
    document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.r === (NAV[r.name] || 'home')));
    const p = cur(); $('#btnProject').textContent = p ? 'Proyek: ' + new URL(p.url).hostname : 'Proyek: –';
    $('#modeBadge').textContent = bridge ? 'LIVE' : 'DEMO'; $('#modeBadge').className = 'badge' + (bridge ? ' live' : '');
    window.scrollTo(0, 0);
  }
  let softT; function softRender() { clearTimeout(softT); softT = setTimeout(() => { const r = route(); if (['browser', 'analyze', 'home'].includes(r.name) && !document.activeElement?.matches('input,textarea,select')) { const y = window.scrollY; render(); window.scrollTo(0, y); } }, 120); }
  window.addEventListener('hashchange', render);

  /* ---------- views ---------- */
  const need = () => cur() ? '' : '<div class="card">Belum ada proyek aktif. <a href="#/home">Mulai pemindaian baru</a> atau pilih dari <a href="#/projects">Proyek</a>.</div>';
  const empty = t => '<div class="item" style="color:var(--mut)">' + t + '</div>';
  const tag = (t, c) => '<span class="tag ' + (c || '') + '">' + esc(t) + '</span>';

  const VIEWS = {
    home() {
      const p = cur(), n = S.projects.length;
      return `<div class="card"><h2>Pindai Website Baru</h2>
      <label>URL (HTTPS)</label><input id="url" type="url" inputmode="url" placeholder="https://contoh.com/#/halaman" value="${esc(p ? p.url : '')}">
      <label>Tingkat akses</label>
      <select id="level"><option value="A">Level A — Public Inspection</option><option value="B">Level B — Authorized Project Access</option><option value="C">Level C — Owner-Controlled Rebuild</option></select>
      <label class="chk" style="margin-top:12px"><input type="checkbox" id="auth"><span>Saya pemilik website ini atau memiliki izin tertulis untuk menganalisis dan menyalin resource-nya.</span></label>
      <div class="note">Hasil bergantung pada akses yang tersedia. Backend privat, logika server game, dan database tidak dapat disalin hanya dari URL — komponen itu dicatat sebagai <i>tidak tersedia</i>, bukan ditebak.</div>
      <div class="note" style="margin-top:10px"><b>Alur disarankan:</b> Preview &amp; Rekam → navigasi halaman yang ingin disalin → Selesai &amp; Copy → Analisis → Ekspor ZIP. Atau pakai Scan cepat tanpa membuka situs.</div>
      <button class="full" id="goPreview">▶ Preview &amp; Rekam (disarankan)</button>
      <button class="sec full" id="go" style="margin-top:8px">Scan cepat (tanpa preview)</button></div>
      ${p ? `<div class="card"><h3>Proyek aktif</h3><div class="u" style="word-break:break-all">${esc(mask(p.url))}</div>
      <div class="meta">${tag('Level ' + p.level)}${tag(p.status, p.status === 'selesai' ? 'ok' : 'warn')}${p.demo ? tag('data demo', 'warn') : ''}</div>
      <div class="grid" style="margin-top:10px"><div class="stat"><b>${p.resources.length}</b><span>Resource</span></div><div class="stat"><b>${Object.keys(p.domains).length}</b><span>Domain</span></div><div class="stat"><b>${p.endpoints.length}</b><span>Endpoint</span></div><div class="stat"><b>${p.resources.filter(r => r.status !== 'ok').length}</b><span>Gagal</span></div></div>
      <div class="row" style="margin-top:12px"><button class="sec" onclick="location.hash='#/browser'">Live feed</button><button class="sec" onclick="location.hash='#/analyze/resources'">Analisis</button><button onclick="location.hash='#/export'">Ekspor ZIP</button></div></div>` : ''}
      <div class="card"><h3>Menu cepat</h3><div class="menu">
      ${[['#/analyze/resources', 'Resource Analyzer'], ['#/analyze/domains', 'Domain Analyzer'], ['#/analyze/server', 'Server Analyzer'], ['#/analyze/api', 'API Analyzer'], ['#/analyze/game', 'Game Resource Analyzer'], ['#/analyze/deps', 'Dependency Map'], ['#/migration', 'API Migration'], ['#/editor', 'Website Editor'], ['#/deploy', 'Deployment Manager']].map(m => `<a href="${m[0]}"><span>${m[1]}</span><span>›</span></a>`).join('')}</div></div>
      <p style="color:var(--mut);font-size:12px;text-align:center">${n} proyek tersimpan · v${TOOL_VERSION}</p>`;
    },
    browser() {
      const p = cur(); if (!p) return need();
      return `<h2>Website Browser</h2><div class="card">
      <div class="row sp"><b style="word-break:break-all">${esc(mask(p.finalUrl))}</b>${tag(p.status, p.status === 'selesai' ? 'ok' : 'warn')}</div>
      <div class="progress" style="margin:10px 0"><i style="width:${progress}%"></i></div>
      <div class="row">${scanning ? '<button class="danger" id="stop">Hentikan</button>' : '<button class="sec" id="rescan">Scan cepat ulang</button>'}
      ${bridge ? '<button id="rePreview">Preview &amp; Rekam</button>' : ''}
      ${bridge ? '<button class="sec" id="openNative">Browser sistem</button>' : ''}</div>
      <div class="note">Saat Preview &amp; Rekam: situs dibuka full-screen, resource yang termuat dicatat seperti sesi merekam. Ketuk <b>Selesai &amp; Copy</b> untuk menyelesaikan.</div>
      ${p.demo ? '<div class="note">Mode DEMO: data di bawah adalah simulasi untuk menguji tampilan, bukan hasil pemindaian sungguhan.</div>' : ''}</div>
      <div class="card"><h3>Live feed</h3><div class="log" id="logbox">${log.map(l => '<div>' + esc(l) + '</div>').join('') || '<div>Menunggu…</div>'}</div></div>`;
    },
    analyze(sub) {
      const p = cur(); const tabs = [['resources', 'Resource'], ['domains', 'Domain'], ['server', 'Server'], ['api', 'API'], ['game', 'Game'], ['deps', 'Dependensi']];
      const hd = '<h2>Analisis</h2><div class="tabs">' + tabs.map(t => `<a href="#/analyze/${t[0]}" class="${(sub || 'resources') === t[0] ? 'active' : ''}">${t[1]}</a>`).join('') + '</div>';
      if (!p) return hd + need();
      return hd + (A[sub || 'resources'] || A.resources)(p);
    },
    editor() {
      const p = cur(); if (!p) return '<h2>Website Editor</h2>' + need();
      const e = p.edits, d = e.draft || (e.draft = JSON.parse(JSON.stringify({ siteName: e.siteName, primary: e.primary, accent: e.accent, logo: e.logo, favicon: e.favicon, apiBase: e.apiBase, replacements: e.replacements })));
      return `<h2>Website Editor</h2><div class="card"><div class="note">Editor hanya mengubah konfigurasi/tema/teks yang dipahami. Versi disimpan sebelum perubahan (backup otomatis).</div>
      <label>Nama website</label><input data-f="siteName" value="${esc(d.siteName)}">
      <div class="row"><div style="flex:1"><label>Warna utama</label><input type="color" data-f="primary" value="${esc(d.primary)}"></div><div style="flex:1"><label>Warna aksen</label><input type="color" data-f="accent" value="${esc(d.accent)}"></div></div>
      <label>URL logo</label><input data-f="logo" placeholder="assets/logo.png" value="${esc(d.logo)}">
      <label>URL favicon</label><input data-f="favicon" placeholder="assets/favicon.ico" value="${esc(d.favicon)}">
      <label>Base URL API (konfigurasi frontend)</label><input data-f="apiBase" placeholder="https://api.domain-saya.com" value="${esc(d.apiBase)}">
      <label>Ganti teks (cari ⇒ ganti, satu per baris: <code>cari=&gt;ganti</code>)</label>
      <textarea data-f="repl">${esc((d.replacements || []).map(r => r.find + '=>' + r.rep).join('\n'))}</textarea></div>
      <div class="card"><h3>Pratinjau</h3><iframe class="prev" sandbox id="prev" srcdoc="${esc(previewDoc(d))}"></iframe></div>
      <div class="row"><button id="saveEdit">Simpan versi</button><button class="sec" id="revert">Batalkan perubahan</button><button class="sec" id="diff">Bandingkan</button></div>
      <div class="card" style="margin-top:12px"><h3>Riwayat versi (${e.versions.length})</h3>${e.versions.map((v, i) => `<div class="item"><div class="row sp"><span>v${i + 1} · ${fmtTime(v.at)}</span><button class="sec" data-restore="${i}">Pulihkan</button></div></div>`).join('') || empty('Belum ada versi tersimpan')}</div><div id="diffOut"></div>`;
    },
    projects() {
      return `<h2>Proyek Tersimpan</h2>` + (S.projects.map(p => `<div class="card"><div class="u" style="word-break:break-all"><b>${esc(mask(p.url))}</b></div><div class="meta">${tag('Level ' + p.level)}${tag(p.status)}${tag('ekspor: ' + p.exportStatus, p.exportStatus === 'sukses' ? 'ok' : '')}${p.demo ? tag('demo', 'warn') : ''}</div>
      <div class="meta">${fmtTime(p.createdAt)} · ${p.resources.length} resource · ${p.endpoints.length} endpoint · ID ${p.id}</div>
      <div class="row" style="margin-top:10px"><button data-open="${p.id}">Buka</button><button class="sec" data-exp="${p.id}">Ekspor</button><button class="danger" data-del="${p.id}">Hapus</button></div></div>`).join('') || '<div class="card">Belum ada proyek.</div>');
    },
    more() {
      const L = [['#/migration', 'API Migration'], ['#/export', 'ZIP Export'], ['#/deploy', 'Deployment Manager'], ['#/history', 'Scan History'], ['#/settings', 'Settings']];
      return `<h2>Lainnya</h2><div class="card menu">${L.map(m => `<a href="${m[0]}"><span>${m[1]}</span><span>›</span></a>`).join('')}</div>`;
    },
    history() {
      return `<h2>Scan History</h2><div class="card">${S.projects.map(p => `<div class="item"><div class="u">${esc(mask(p.url))}</div><div class="meta"><span>${fmtTime(p.createdAt)}</span>${tag('Level ' + p.level)}${tag(p.status)}</div></div>`).join('') || empty('Riwayat kosong')}</div>`;
    },
    settings() {
      const s = S.settings;
      return `<h2>Settings</h2><div class="card"><label>Batas jumlah resource</label><input type="number" id="sMax" min="10" max="5000" value="${s.maxResources}">
      <label>Batas waktu pemindaian (detik)</label><input type="number" id="sTime" min="5" max="600" value="${s.timeout}">
      <label class="chk" style="margin-top:12px"><input type="checkbox" id="sMask" ${s.mask ? 'checked' : ''}><span>Samarkan token, cookie, dan kredensial di laporan</span></label>
      <div class="row" style="margin-top:12px"><button id="sSave">Simpan</button><button class="danger" id="sWipe">Hapus semua data</button></div></div>
      <div class="card"><h3>Keamanan</h3><p style="font-size:13px;color:var(--mut)">Validasi URL di UI hanya lapisan pertama. Validasi akhir (localhost, IP internal, metadata cloud, redirect, batas ukuran, ZIP Slip) wajib dilakukan lagi di sisi native/server.</p></div>`;
    },
    migration() {
      const p = cur(); if (!p) return '<h2>API Migration</h2>' + need();
      const m = p.migration; if (!m.map.length) m.map = p.endpoints.filter(e => !e.ws).map(e => ({ old: e.path, neu: '/api/v1' + e.path.replace(/^\/api/, ''), status: 'Direncanakan' }));
      return `<h2>API Migration</h2><div class="card"><div class="note">Hanya untuk sistem milik sendiri/berizin. Token API lama tidak boleh disalin atau dipakai ulang. Path baru di bawah hanyalah usulan yang bisa Anda ubah.</div>
      <label>Mode integrasi</label><select id="mMode">${[['direct', 'Mode 1 — Direct New API'], ['adapter', 'Mode 2 — Adapter'], ['mock', 'Mode 3 — Mock API']].map(o => `<option value="${o[0]}" ${m.mode === o[0] ? 'selected' : ''}>${o[1]}</option>`).join('')}</select>
      <label>Base URL API baru</label><input id="mBase" placeholder="https://api.domain-saya.com" value="${esc(m.newBase)}">
      ${m.mode === 'mock' ? '<div class="note">Mock API hanya untuk menguji UI — bukan layanan produksi dan tidak menyimpan data asli.</div>' : ''}</div>
      <div class="card"><h3>Tabel pemetaan kontrak</h3><table><tr><th>API lama</th><th>API baru</th><th>Status</th></tr>
      ${m.map.map((r, i) => `<tr><td>${esc(r.old)}</td><td><input data-mi="${i}" value="${esc(r.neu)}"></td><td><select data-ms="${i}">${['Direncanakan', 'Diimplementasi', 'Diuji', 'Cutover'].map(s => `<option ${r.status === s ? 'selected' : ''}>${s}</option>`).join('')}</select></td></tr>`).join('') || '<tr><td colspan="3">Belum ada endpoint teramati.</td></tr>'}</table>
      <div class="row" style="margin-top:10px"><button id="mAdd" class="sec">+ Baris</button><button id="mSave">Simpan</button><button id="mGen" class="sec">Buat adapter JS</button></div></div><pre id="mOut" hidden></pre>`;
    },
    export() {
      const p = cur(); if (!p) return '<h2>ZIP Export</h2>' + need();
      return `<h2>ZIP Export</h2><div class="card"><div class="note">Struktur: project.json, manifest.json, report/*, website/*, backend/, deployment/, checksums/SHA256SUMS.txt. Rahasia produksi tidak dimasukkan. Ekspor ditandai sukses hanya setelah ZIP diverifikasi.</div>
      <label class="chk"><input type="checkbox" id="xContent" checked><span>Sertakan isi resource yang tersedia dari native</span></label>
      <div class="meta" style="margin:8px 0">Status ekspor: ${tag(p.exportStatus, p.exportStatus === 'sukses' ? 'ok' : '')}</div>
      <button class="full" id="xGo">Bangun &amp; Verifikasi ZIP</button><div id="xOut" style="margin-top:10px"></div></div>`;
    },
    deploy() {
      const p = cur(); if (!p) return '<h2>Deployment Manager</h2>' + need();
      const d = p.deploy;
      return `<h2>Deployment Manager</h2><div class="card"><div class="note">Pemindahan domain/DNS butuh kendali atas domain tujuan. Hosting hanya untuk domain milik Anda.</div>
      <label>Nama proyek</label><input data-d="name" value="${esc(d.name)}" placeholder="situs-saya"><label>Domain baru</label><input data-d="domain" value="${esc(d.domain)}" placeholder="situs-saya.com">
      <label>Subdomain API</label><input data-d="apiDomain" value="${esc(d.apiDomain)}" placeholder="api.situs-saya.com"><label>Nama database D1</label><input data-d="d1" value="${esc(d.d1)}" placeholder="situs-saya-db">
      <div class="row" style="margin-top:12px"><button id="dGen">Buat konfigurasi contoh</button></div></div><pre id="dOut" hidden></pre>
      <div class="card"><h3>Checklist cutover</h3>${['Frontend diuji di Pages (preview)', 'API baru lulus uji kontrak', 'Autentikasi baru diuji (login/logout/expired)', 'CORS &amp; rate limiting dikonfigurasi', 'DNS domain milik sendiri diarahkan', 'Rollback siap'].map(c => `<label class="chk"><input type="checkbox"><span>${c}</span></label>`).join('')}</div>`;
    }
  };

  const A = {
    resources(p) {
      const cats = ['Semua', ...new Set(p.resources.map(r => r.category))]; const f = A._f || 'Semua';
      const list = p.resources.filter(r => f === 'Semua' || r.category === f);
      return `<div class="row" style="margin-bottom:8px">${cats.map(c => `<button class="chip ${c === f ? 'on' : ''}" data-cat="${esc(c)}">${esc(c)}</button>`).join('')}</div>
      <div class="card">${list.map(r => `<div class="item"><div class="u">${esc(mask(r.url))}</div><div class="meta">${tag(r.category, 'acc')}${tag(r.status === 'ok' ? 'tersimpan' : 'gagal', r.status === 'ok' ? 'ok' : 'err')}<span>${fmtSize(r.size)}</span>${r.dup ? tag('duplikat', 'warn') : ''}${r.error ? '<span>' + esc(r.error) + '</span>' : ''}</div></div>`).join('') || empty('Belum ada resource')}</div>
      <div class="note">DOM hasil render bukan pengganti source code backend. File yang tidak dapat diambil dicatat apa adanya.</div>`;
    },
    domains(p) {
      const base = new URL(p.finalUrl || p.url).hostname;
      return `<div class="card">${Object.values(p.domains).map(d => `<div class="item"><b>${esc(d.host)}</b><div class="meta">${tag(hostKind(d.host, base, p), 'acc')}<span>${d.count}× · sumber: ${esc(d.sources.join(', '))}</span></div></div>`).join('') || empty('Belum ada domain')}</div>
      ${p.redirects.length ? `<div class="card"><h3>Redirect</h3>${p.redirects.map(r => `<div class="item u">${esc(mask(r.from))} → ${esc(mask(r.to))}</div>`).join('')}</div>` : ''}
      <div class="note">Hostname yang muncul di halaman tidak otomatis dimiliki website tersebut — kategori adalah dugaan berdasarkan sumber penemuan.</div>`;
    },
    server(p) {
      const hs = Object.entries(p.server);
      return hs.map(([h, s]) => `<div class="card"><b>${esc(h)}</b><div class="meta">${tag('HTTP ' + s.status, s.status < 400 ? 'ok' : 'err')}${s.ttfb != null ? '<span>TTFB ' + s.ttfb + ' ms</span>' : ''}${s.ip ? '<span>IP ' + esc(s.ip) + '</span>' : ''}</div>
      <h3 style="margin-top:10px">Header (disamarkan)</h3><pre>${esc(Object.entries(s.headers || {}).map(([k, v]) => k + ': ' + v).join('\n') || '–')}</pre>
      ${s.tls ? '<h3>TLS</h3><pre>' + esc(JSON.stringify(s.tls, null, 2)) + '</pre>' : ''}</div>`).join('') || '<div class="card">Belum ada data server.</div>'
        + '<div class="note">Yang diamati ≠ yang diduga. Konfigurasi server, log, environment, dan database hanya tersedia dengan akses pemilik (Level B/C). Header "server" saja tidak cukup untuk memastikan teknologi backend.</div>';
    },
    api(p) {
      return `<div class="card">${p.endpoints.map(e => `<div class="item"><div class="u"><b>${esc(e.method)}</b> ${esc(e.host)}${esc(e.path)}</div><div class="meta">${tag(e.category, 'acc')}${e.status != null ? tag('HTTP ' + e.status, e.status < 400 ? 'ok' : 'warn') : ''}<span>${esc(e.owner)}</span>${tag(e.schema ? 'skema diketahui' : 'skema belum diketahui')}</div></div>`).join('') || empty('Belum ada endpoint teramati')}</div>
      <div class="note">Klasifikasi adalah perkiraan. Endpoint yang tidak teramati bukan berarti tidak ada.</div>`;
    },
    game(p) {
      const g = p.endpoints.filter(e => e.game || e.category === 'Game' || e.category === 'WebSocket');
      const media = p.resources.filter(r => ['Gambar', 'Audio', 'Font', 'Video', 'JS'].includes(r.category));
      const cnt = {}; media.forEach(r => cnt[r.category] = (cnt[r.category] || 0) + 1);
      return `<div class="card"><h3>Resource game</h3><div class="grid">${Object.entries(cnt).map(([k, v]) => `<div class="stat"><b>${v}</b><span>${k}</span></div>`).join('') || empty('–')}</div></div>
      <div class="card"><h3>Endpoint &amp; WebSocket game</h3>${g.map(e => `<div class="item"><div class="u">${esc(e.method)} ${esc(e.host)}${esc(e.path)}</div><div class="meta">${tag(e.game || e.category, 'acc')}</div></div>`).join('') || empty('Tidak ada yang teramati')}</div>
      <div class="note">Hanya inventaris untuk game milik sendiri/berizin. Aturan gameplay, RNG, matchmaking, validasi hasil, dan skema database memerlukan source code/akses pemilik — file frontend tidak membuktikan logika server. Modul ini tidak untuk mengubah hasil permainan atau melewati autentikasi/DRM.</div>`;
    },
    deps(p) {
      const pages = {}; p.resources.forEach(r => (pages[r.page] = pages[r.page] || []).push(r));
      const js = p.resources.filter(r => r.category === 'JS');
      const failed = p.resources.filter(r => r.status !== 'ok');
      const ext = Object.values(p.domains).filter(d => hostKind(d.host, new URL(p.url).hostname, p) === 'Eksternal' || hostKind(d.host, new URL(p.url).hostname, p) === 'CDN');
      return `<div class="card tree"><h3>Halaman → Resource → API</h3>${Object.entries(pages).map(([pg, rs]) => `<div class="n">📄 ${esc(mask(pg))}</div><div class="c">${rs.slice(0, 40).map(r => `<div class="n">${esc(r.category)} · ${esc(mask(r.url))}</div>`).join('')}
      ${js.length ? '<div class="n">⚙ dipanggil dari JS →</div><div class="c">' + p.endpoints.map(e => `<div class="n">${esc(e.method)} ${esc(e.path)} <span style="color:var(--mut)">(${esc(e.category)})</span></div>`).join('') + '</div>' : ''}</div>`).join('') || empty('Belum ada data')}</div>
      <div class="card"><h3>Dependensi eksternal</h3>${ext.map(d => `<div class="item u">${esc(d.host)}</div>`).join('') || empty('Tidak ada')}</div>
      <div class="card"><h3>Gagal dimuat (${failed.length})</h3>${failed.map(r => `<div class="item u">${esc(mask(r.url))}<div class="meta">${esc(r.error || 'gagal')}</div></div>`).join('') || empty('Tidak ada')}</div>`;
    }
  };

  function previewDoc(d) {
    return `<!doctype html><meta name=viewport content="width=device-width"><body style="margin:0;font-family:sans-serif"><div style="background:${esc(d.primary)};color:#fff;padding:14px;display:flex;gap:10px;align-items:center">${d.logo ? '<span style="width:28px;height:28px;border-radius:8px;background:rgba(255,255,255,.35);display:inline-block"></span>' : ''}<b>${esc(d.siteName || 'Nama Website')}</b></div><div style="padding:16px"><h3>Halo dunia</h3><p>Contoh teks pratinjau tema.</p><button style="background:${esc(d.accent)};border:0;padding:10px 14px;border-radius:8px">Tombol aksen</button></div>`;
  }

  /* ---------- bindings ---------- */
  const on = (s, ev, fn) => { const e = $(s); if (e) e.addEventListener(ev, fn); };
  const onAll = (s, ev, fn) => document.querySelectorAll(s).forEach(e => e.addEventListener(ev, () => fn(e)));
  const BIND = {
    home() {
      on('#goPreview', 'click', () => startScan($('#url').value, $('#level').value, $('#auth').checked, 'preview'));
      on('#go', 'click', () => startScan($('#url').value, $('#level').value, $('#auth').checked, 'scan'));
    },
    browser() {
      on('#stop', 'click', stopScan);
      on('#rescan', 'click', () => { const p = cur(); startScan(p.url, p.level, true, 'scan'); });
      on('#rePreview', 'click', () => { const p = cur(); if (p) startScan(p.url, p.level, true, 'preview'); });
      on('#openNative', 'click', () => bridge && bridge.openUrl(cur().url));
      const b = $('#logbox'); if (b) b.scrollTop = b.scrollHeight;
    },
    analyze() { onAll('[data-cat]', 'click', b => { A._f = b.dataset.cat; render(); }); },
    editor() {
      const p = cur(); if (!p) return; const e = p.edits, d = e.draft;
      const upd = () => { $('#prev').srcdoc = previewDoc(d); save(); };
      onAll('[data-f]', 'input', i => { const f = i.dataset.f; if (f === 'repl') d.replacements = i.value.split('\n').map(l => l.split('=>')).filter(a => a.length === 2 && a[0]).map(a => ({ find: a[0], rep: a[1] })); else d[f] = i.value; upd(); });
      on('#saveEdit', 'click', () => { e.versions.push({ at: Date.now(), snap: JSON.stringify({ siteName: e.siteName, primary: e.primary, accent: e.accent, logo: e.logo, favicon: e.favicon, apiBase: e.apiBase, replacements: e.replacements }) }); Object.assign(e, JSON.parse(JSON.stringify(d))); save(); toast('Versi tersimpan (backup versi sebelumnya)'); render(); });
      on('#revert', 'click', () => { e.draft = null; save(); render(); toast('Perubahan dibatalkan'); });
      on('#diff', 'click', () => { const keys = ['siteName', 'primary', 'accent', 'logo', 'favicon', 'apiBase']; const rows = keys.filter(k => e[k] !== d[k]).map(k => `<tr><td>${k}</td><td>${esc(e[k] || '–')}</td><td>${esc(d[k] || '–')}</td></tr>`); $('#diffOut').innerHTML = `<div class="card"><h3>Perubahan belum disimpan</h3><table><tr><th>Field</th><th>Tersimpan</th><th>Draft</th></tr>${rows.join('') || '<tr><td colspan=3>Tidak ada perubahan</td></tr>'}</table></div>`; });
      onAll('[data-restore]', 'click', b => { const v = e.versions[+b.dataset.restore]; Object.assign(e, JSON.parse(v.snap)); e.draft = null; save(); render(); toast('Versi dipulihkan'); });
    },
    projects() {
      onAll('[data-open]', 'click', b => { S.currentId = b.dataset.open; save(); location.hash = '#/home'; render(); });
      onAll('[data-exp]', 'click', b => { S.currentId = b.dataset.exp; save(); location.hash = '#/export'; });
      onAll('[data-del]', 'click', b => { if (confirm('Hapus proyek ini?')) { S.projects = S.projects.filter(p => p.id !== b.dataset.del); if (S.currentId === b.dataset.del) S.currentId = null; save(); render(); } });
    },
    settings() {
      on('#sSave', 'click', () => { S.settings = { maxResources: +$('#sMax').value || 500, timeout: +$('#sTime').value || 30, mask: $('#sMask').checked }; save(); toast('Tersimpan'); });
      on('#sWipe', 'click', () => { if (confirm('Hapus SEMUA data aplikasi?')) { localStorage.removeItem(KEY); S = load(); render(); } });
    },
    migration() {
      const p = cur(); if (!p) return; const m = p.migration;
      on('#mMode', 'change', e => { m.mode = e.target.value; save(); render(); }); on('#mBase', 'input', e => { m.newBase = e.target.value; save(); });
      onAll('[data-mi]', 'input', i => { m.map[+i.dataset.mi].neu = i.value; save(); }); onAll('[data-ms]', 'change', s => { m.map[+s.dataset.ms].status = s.value; save(); });
      on('#mAdd', 'click', () => { m.map.push({ old: '/api/', neu: '/api/v1/', status: 'Direncanakan' }); save(); render(); });
      on('#mSave', 'click', () => { save(); toast('Pemetaan tersimpan'); });
      on('#mGen', 'click', () => { const o = $('#mOut'); o.hidden = false; o.textContent = genAdapter(m); });
    },
    export() { on('#xGo', 'click', doExport); },
    deploy() {
      const p = cur(); if (!p) return;
      onAll('[data-d]', 'input', i => { p.deploy[i.dataset.d] = i.value; save(); });
      on('#dGen', 'click', () => { const o = $('#dOut'); o.hidden = false; o.textContent = deployFiles(p)['wrangler.toml.example'] + '\n\n' + deployFiles(p)['README.md']; });
    }
  };
  $('#btnProject').addEventListener('click', () => location.hash = '#/projects');

  function genAdapter(m) {
    const map = {}; m.map.forEach(r => map[r.old] = r.neu);
    return `// Adapter kompatibilitas (mode: ${m.mode})\n// Frontend tetap memanggil path lama; adapter menerjemahkan ke API baru.\nconst NEW_BASE = ${JSON.stringify(m.newBase || 'https://api.domain-saya.com')};\nconst MAP = ${JSON.stringify(map, null, 2)};\nconst _fetch = window.fetch.bind(window);\nwindow.fetch = (input, init) => {\n  const url = new URL(typeof input === 'string' ? input : input.url, location.href);\n  const to = MAP[url.pathname];\n  if (!to) return _fetch(input, init);\n  return _fetch(NEW_BASE + to + url.search, init);\n  // TODO: terjemahkan bentuk request/response bila kontrak berbeda\n};\n`;
  }
  function deployFiles(p) {
    const d = p.deploy, n = d.name || 'situs-saya';
    return {
      'wrangler.toml.example': `name = "${n}-api"\nmain = "src/index.js"\ncompatibility_date = "2026-10-01"\n\n[[d1_databases]]\nbinding = "DB"\ndatabase_name = "${d.d1 || n + '-db'}"\ndatabase_id = "ISI_DENGAN_ID_D1_ANDA"\n\n# Rahasia (JWT_SECRET dll.) JANGAN ditulis di sini: wrangler secret put JWT_SECRET\n`,
      'README.md': `# Deployment ${n}\n\n- Frontend: Cloudflare Pages (folder website/)\n- Backend: Cloudflare Worker + D1\n- Domain: ${d.domain || '(belum diisi)'}  API: ${d.apiDomain || '(belum diisi)'}\n\nCatatan: domain harus milik Anda. Rahasia produksi tidak disertakan.\n`
    };
  }

  /* ---------- ZIP (store, tanpa kompresi) ---------- */
  const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
  const crc32 = b => { let c = 0xFFFFFFFF; for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 255] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; };
  function buildZip(files) {
    const enc = new TextEncoder(), parts = [], cd = []; let off = 0;
    for (const f of files) {
      const name = enc.encode(f.path), data = f.data, crc = crc32(data);
      const h = new DataView(new ArrayBuffer(30)); h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x0800, true); h.setUint16(8, 0, true); h.setUint16(10, 0, true); h.setUint16(12, 0x21, true); h.setUint32(14, crc, true); h.setUint32(18, data.length, true); h.setUint32(22, data.length, true); h.setUint16(26, name.length, true); h.setUint16(28, 0, true);
      parts.push(new Uint8Array(h.buffer), name, data);
      const c = new DataView(new ArrayBuffer(46)); c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true); c.setUint16(12, 0, true); c.setUint16(14, 0x21, true); c.setUint32(16, crc, true); c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, name.length, true); c.setUint32(42, off, true);
      cd.push(new Uint8Array(c.buffer), name); off += 30 + name.length + data.length;
    }
    const cdSize = cd.reduce((a, b) => a + b.length, 0), e = new DataView(new ArrayBuffer(22));
    e.setUint32(0, 0x06054b50, true); e.setUint16(8, files.length, true); e.setUint16(10, files.length, true); e.setUint32(12, cdSize, true); e.setUint32(16, off, true);
    const all = [...parts, ...cd, new Uint8Array(e.buffer)], total = all.reduce((a, b) => a + b.length, 0), out = new Uint8Array(total); let o = 0; for (const a of all) { out.set(a, o); o += a.length; } return out;
  }
  function verifyZip(buf, expect) { // baca ulang central directory + cek CRC
    const v = new DataView(buf.buffer, buf.byteOffset, buf.byteLength); let e = buf.length - 22; if (v.getUint32(e, true) !== 0x06054b50) return 'EOCD tidak ditemukan';
    const n = v.getUint16(e + 10, true); if (n !== expect) return 'jumlah entri tidak cocok'; let p = v.getUint32(e + 16, true);
    for (let i = 0; i < n; i++) { if (v.getUint32(p, true) !== 0x02014b50) return 'central directory rusak'; const crc = v.getUint32(p + 16, true), sz = v.getUint32(p + 20, true), nl = v.getUint16(p + 28, true), lo = v.getUint32(p + 42, true); const name = new TextDecoder().decode(buf.subarray(p + 46, p + 46 + nl)); if (name.includes('..') || name.startsWith('/')) return 'path tidak aman: ' + name; const ds = lo + 30 + v.getUint16(lo + 26, true) + v.getUint16(lo + 28, true); if (crc32(buf.subarray(ds, ds + sz)) !== crc) return 'CRC salah: ' + name; p += 46 + nl; }
    return null;
  }
  async function sha256(u8) { if (!(window.crypto && crypto.subtle)) return null; const h = await crypto.subtle.digest('SHA-256', u8); return [...new Uint8Array(h)].map(b => b.toString(16).padStart(2, '0')).join(''); }

  async function doExport() {
    const p = cur(), out = $('#xOut'), inc = $('#xContent').checked; out.textContent = 'Membangun…';
    try {
      const enc = new TextEncoder(), J = o => JSON.stringify(o, null, 2), files = [], add = (path, txt) => files.push({ path: safePath(path), data: typeof txt === 'string' ? enc.encode(txt) : txt });
      const rs = p.resources.map(r => ({ url: mask(r.url), category: r.category, mime: r.mime, size: r.size, status: r.status, hash: r.hash, path: r.path, error: r.error, checkedAt: r.checkedAt }));
      const lim = ['Backend privat, database, dan logika server tidak dapat diperoleh dari URL frontend.', 'Resource yang tidak berhasil diambil dicatat pada resources.json.', 'Klasifikasi endpoint/domain adalah perkiraan berdasarkan bukti yang teramati.', 'Endpoint yang tidak teramati tidak berarti tidak ada.', 'DOM hasil render bukan pengganti source code.', p.demo ? 'PERHATIAN: proyek ini berasal dari mode DEMO (data simulasi).' : 'Hasil terbatas pada akses yang tersedia (Level ' + p.level + ').'];
      add('project.json', J({ id: p.id, url: mask(p.url), finalUrl: mask(p.finalUrl), level: p.level, createdAt: new Date(p.createdAt).toISOString(), demo: p.demo, edits: p.edits ? { siteName: p.edits.siteName, primary: p.edits.primary, accent: p.edits.accent, apiBase: p.edits.apiBase } : {}, migration: p.migration, deploy: p.deploy }));
      add('README.md', `# ${p.edits.siteName || new URL(p.url).hostname}\n\nDiekspor oleh Ambrus ${TOOL_VERSION} pada ${new Date().toISOString()}.\n\nJenis hasil: resource copy (parsial). Frontend rebuild & full migration TIDAK otomatis tercapai.\nLihat report/limitations.json.\n`);
      add('report/analysis.json', J({ stats: { resources: p.resources.length, domains: Object.keys(p.domains).length, endpoints: p.endpoints.length, requests: p.requests.length } }));
      add('report/resources.json', J(rs)); add('report/domains.json', J(Object.values(p.domains))); add('report/servers.json', J({ observed: p.server, dns: p.dns, note: 'Hanya fakta teramati.' }));
      add('report/endpoints.json', J(p.endpoints.map(e => ({ ...e, key: undefned(e.key) })))); add('report/dependencies.json', J({ pages: p.pages, redirects: p.redirects.map(r => ({ from: mask(r.from), to: mask(r.to) })), failed: rs.filter(r => r.status !== 'ok') }));
      add('report/limitations.json', J(lim));
      const withContent = p.resources.filter(r => r.status === 'ok' && typeof r.content === 'string');
      if (inc) withContent.forEach(r => add('website/' + (r.path || 'file'), r.content));
      else add('website/README.md', 'Isi resource tidak disertakan.');
      add('backend/README.md', 'Backend baru belum dikembangkan. Lihat migrasi API di project.json.'); add('backend/api-spec.json', J({ mode: p.migration.mode, newBase: p.migration.newBase, map: p.migration.map }));
      const d = deployFiles(p); add('deployment/README.md', d['README.md']); add('deployment/deployment-config.example', d['wrangler.toml.example']);
      const manifest = { projectId: p.id, url: mask(p.url), finalUrl: mask(p.finalUrl), analyzedAt: new Date(p.createdAt).toISOString(), toolVersion: TOOL_VERSION, files: [], resources: rs.length, limitations: lim };
      let sums = ''; for (const f of files) { const h = await sha256(f.data); manifest.files.push({ path: f.path, size: f.data.length, sha256: h }); if (h) sums += h + '  ' + f.path + '\n'; }
      add('manifest.json', J(manifest)); add('checksums/SHA256SUMS.txt', sums || '(SHA-256 tidak tersedia di konteks ini)\n');
      const zip = buildZip(files), err = verifyZip(zip, files.length);
      if (err) { p.exportStatus = 'gagal'; save(); out.innerHTML = '<span class="tag err">Verifikasi gagal: ' + esc(err) + '</span>'; return; }
      p.exportStatus = 'sukses'; save();
      const name = 'ambrus-' + new URL(p.url).hostname + '-' + p.id + '.zip';
      if (bridge && bridge.saveZip) { let b = ''; for (let i = 0; i < zip.length; i += 0x8000) b += String.fromCharCode.apply(null, zip.subarray(i, i + 0x8000)); bridge.saveZip(btoa(b), name); }
      else { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([zip], { type: 'application/zip' })); a.download = name; document.body.appendChild(a); a.click(); a.remove(); }
      out.innerHTML = `<span class="tag ok">ZIP terverifikasi</span> ${files.length} file · ${fmtSize(zip.length)}${withContent.length === 0 ? '<div class="note">Tidak ada isi resource dari native — folder website/ kosong. Itu normal di mode DEMO.</div>' : ''}`;
    } catch (e) { p.exportStatus = 'gagal'; save(); out.innerHTML = '<span class="tag err">Gagal: ' + esc(e.message) + '</span>'; }
  }
  function undefned(k) { return undefined; }

  render();
})();
