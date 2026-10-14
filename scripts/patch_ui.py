#!/usr/bin/env python3
from pathlib import Path

p = Path("app/src/main/assets/ambrus/app.js")
t = p.read_text(encoding="utf-8")

# 1) Inject HTML editor field before Pratinjau in editor VIEW
marker = '<div class="card"><h3>Pratinjau</h3><iframe class="prev" sandbox id="prev"'
if marker in t and "data-f=\"htmlDraft\"" not in t:
    inject = (
        '<label>HTML dari ZIP (edit web)</label>\n'
        '      <textarea data-f="htmlDraft" rows="10" style="width:100%;font-family:monospace;font-size:12px;'
        'background:var(--panel2);color:var(--tx);border:1px solid var(--line);border-radius:10px;padding:10px">'
        '${esc((d.htmlDraft != null ? d.htmlDraft : (e.htmlDraft || (p.websiteFiles && (p.websiteFiles["index.html"] || Object.values(p.websiteFiles)[0])) || "") ))}'
        '</textarea></div>\n'
        '      <div class="card"><h3>Pratinjau</h3><iframe class="prev" sandbox id="prev"'
    )
    t = t.replace(marker, inject, 1)
    print("injected htmlDraft field")
else:
    print("skip inject", "htmlDraft" in t, marker in t)

# 2) Patch editor draft init to include htmlDraft
old_init = "JSON.parse(JSON.stringify({ siteName: e.siteName, primary: e.primary, accent: e.accent, logo: e.logo, favicon: e.favicon, apiBase: e.apiBase, replacements: e.replacements }))"
new_init = "JSON.parse(JSON.stringify({ siteName: e.siteName, primary: e.primary, accent: e.accent, logo: e.logo, favicon: e.favicon, apiBase: e.apiBase, replacements: e.replacements, htmlDraft: e.htmlDraft || (p.websiteFiles && (p.websiteFiles['index.html'] || Object.values(p.websiteFiles)[0])) || '' }))"
if old_init in t:
    t = t.replace(old_init, new_init, 1)
    print("draft init patched")
else:
    print("draft init not found")

# 3) Patch editor bind upd + save to handle htmlDraft
old_upd = "const upd = () => { $('#prev').srcdoc = previewDoc(d); save(); };"
new_upd = (
    "const upd = () => { const iframe = $('#prev'); if (!iframe) return; "
    "if (d.htmlDraft && String(d.htmlDraft).length > 20) iframe.srcdoc = d.htmlDraft; "
    "else iframe.srcdoc = previewDoc(d); save(); };"
)
if old_upd in t:
    t = t.replace(old_upd, new_upd, 1)
    print("upd patched")
else:
    print("upd not found")

old_save = (
    "on('#saveEdit', 'click', () => { e.versions.push({ at: Date.now(), snap: JSON.stringify({ siteName: e.siteName, primary: e.primary, accent: e.accent, logo: e.logo, favicon: e.favicon, apiBase: e.apiBase, replacements: e.replacements }) }); Object.assign(e, JSON.parse(JSON.stringify(d))); save(); toast('Versi tersimpan (backup versi sebelumnya)'); render(); });"
)
new_save = (
    "on('#saveEdit', 'click', () => { e.versions.push({ at: Date.now(), snap: JSON.stringify({ siteName: e.siteName, primary: e.primary, accent: e.accent, logo: e.logo, favicon: e.favicon, apiBase: e.apiBase, replacements: e.replacements, htmlDraft: e.htmlDraft }) }); Object.assign(e, JSON.parse(JSON.stringify(d))); e.htmlDraft = d.htmlDraft || e.htmlDraft; save(); toast('Versi tersimpan'); render(); });"
)
if old_save in t:
    t = t.replace(old_save, new_save, 1)
    print("save patched")
else:
    print("save not found")

# 4) Export includes htmlDraft
marker2 = "if (inc) withContent.forEach(r => add('website/' + (r.path || 'file'), r.content));"
extra = (
    marker2
    + "\n      if (p.edits && p.edits.htmlDraft) add('website/index.html', p.edits.htmlDraft);"
    + "\n      else if (p.websiteFiles) Object.keys(p.websiteFiles).forEach(k => add('website/' + k, p.websiteFiles[k]));"
)
if marker2 in t and "p.edits.htmlDraft" not in t:
    t = t.replace(marker2, extra, 1)
    print("export patched")
else:
    print("export skip")

p.write_text(t, encoding="utf-8")
print("done", p.stat().st_size)
