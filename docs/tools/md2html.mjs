// Turns a document's Markdown (a small, known subset: headings, tables, lists, code, links) into one self-contained HTML page.
// Usage: node docs/tools/md2html.mjs <in.md> <out.html> [config.json]   (config: {"themeKey": "...", "stats": [["label","value"], ...]})
// The stack guide uses the defaults; the trust policy uses docs/tools/policy-config.json.
import { readFileSync, writeFileSync } from 'node:fs';

const [, , inPath, outPath, cfgPath] = process.argv;
const cfg = cfgPath ? JSON.parse(readFileSync(cfgPath, 'utf8')) : {};
const stats = cfg.stats ?? [['Hub','Java 21, Spring Boot 4.1'],['Agent and CLI','Go 1.27, one program'],['Dashboard','Svelte 5, Vite 6, uPlot'],['Data','SQLite, one file']];
const themeKey = cfg.themeKey ?? 'svcmon-guide-theme';
const lines = readFileSync(inPath, 'utf8').replace(/\r\n/g, '\n').split('\n');

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function inline(text) {
  const codes = [];
  let s = text.replace(/`([^`]+)`/g, (_, c) => {
    codes.push(`<code>${esc(c)}</code>`);
    return `\u0000${codes.length - 1}\u0000`;
  });
  s = esc(s);
  s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" rel="noopener">$1</a>');
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, (m, pre, url) => {
    const tail = url.match(/[.,;:]+$/)?.[0] ?? '';
    const clean = tail ? url.slice(0, -tail.length) : url;
    return `${pre}<a href="${clean}" rel="noopener">${clean}</a>${tail}`;
  });
  return s.replace(/\u0000(\d+)\u0000/g, (_, i) => codes[Number(i)]);
}

const slug = (t) => t.replace(/^\d+\.\s*/, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

const diagram = `
<figure class="diagram" aria-label="How the servers, the hub, the dashboard and the alert channels connect">
  <div class="dg-row">
    <div class="dg-box"><b>web01</b><small>agent</small></div>
    <div class="dg-box"><b>db01</b><small>agent</small></div>
    <div class="dg-box"><b>build01</b><small>agent</small></div>
  </div>
  <div class="dg-arrow">monitored servers only call out &darr;</div>
  <div class="dg-row dg-main">
    <div class="dg-box dg-lb"><b>Load balancer</b><small>ends TLS</small></div>
    <div class="dg-arrow dg-side">&rarr;</div>
    <div class="dg-box dg-hub"><b>The hub</b><small>Java, Spring Boot: scheduler, REST API, alerts, one SQLite file</small></div>
  </div>
  <div class="dg-arrow">&darr;</div>
  <div class="dg-row">
    <div class="dg-box dg-ui"><b>Dashboard</b><small>Svelte, served by the hub</small></div>
    <div class="dg-box dg-ui"><b>CLI</b><small>svcmon, the same Go program as the agent</small></div>
    <div class="dg-box dg-out"><b>Alert channels</b><small>Slack, Discord, email, webhook</small></div>
  </div>
  <div class="dg-note">Optional sign-in: Keycloak or another OpenID Connect provider, checked by the hub itself</div>
</figure>`;

let title = '';
let lead = '';
const sections = [];
let cur = null;
const out = [];
const push = (h) => (cur ? cur.html.push(h) : out.push(h));

for (let i = 0; i < lines.length; ) {
  const line = lines[i];
  if (line.startsWith('# ')) { title = line.slice(2).trim(); i++; continue; }
  if (line.startsWith('## ')) {
    const t = line.slice(3).trim();
    cur = { title: t, id: slug(t), html: [] };
    sections.push(cur);
    i++; continue;
  }
  if (line.startsWith('### ')) { push(`<h3>${inline(line.slice(4).trim())}</h3>`); i++; continue; }
  if (line.startsWith('```')) {
    const lang = line.slice(3).trim();
    const body = [];
    i++;
    while (i < lines.length && !lines[i].startsWith('```')) body.push(lines[i++]);
    i++;
    push(lang === 'sketch' ? diagram : `<pre><code>${esc(body.join('\n'))}</code></pre>`);
    continue;
  }
  if (line.startsWith('![')) {
    // Pictures on consecutive lines sit side by side; a title of "narrow" keeps a tall phone picture small.
    const figs = [];
    while (i < lines.length && lines[i].startsWith('![')) {
      const m = lines[i++].match(/^!\[([^\]]*)\]\(([^)\s]+)(?:\s+"([^"]*)")?\)\s*$/);
      if (m) {
        figs.push(`<figure${m[3] === 'narrow' ? ' class="narrow"' : ''}><a href="${esc(m[2])}"><img src="${esc(m[2])}" alt="${esc(m[1])}" loading="lazy"></a><figcaption>${inline(m[1])}</figcaption></figure>`);
      }
    }
    push(`<div class="shots">${figs.join('')}</div>`);
    continue;
  }
  if (line.startsWith('|')) {
    const rows = [];
    while (i < lines.length && lines[i].startsWith('|')) rows.push(lines[i++]);
    const cells = (r) => r.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
    const head = cells(rows[0]);
    const body = rows.slice(2).map(cells);
    push(
      '<div class="tablewrap"><table><thead><tr>' + head.map((c) => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>' +
        body.map((r) => '<tr>' + r.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') + '</tbody></table></div>',
    );
    continue;
  }
  if (/^- /.test(line) || /^\d+\. /.test(line)) {
    const ordered = /^\d+\. /.test(line);
    const items = [];
    while (i < lines.length && (ordered ? /^\d+\. /.test(lines[i]) : /^- /.test(lines[i]))) {
      items.push(lines[i++].replace(ordered ? /^\d+\. / : /^- /, ''));
    }
    const tag = ordered ? 'ol' : 'ul';
    push(`<${tag}>` + items.map((t) => `<li>${inline(t)}</li>`).join('') + `</${tag}>`);
    continue;
  }
  if (line.trim() === '') { i++; continue; }
  const para = [];
  while (i < lines.length && lines[i].trim() !== '' && !/^(#|\||```|- |\d+\. |!\[)/.test(lines[i])) para.push(lines[i++]);
  const text = para.join(' ');
  if (!cur && !lead) lead = text;
  else push(`<p>${inline(text)}</p>`);
}

const nav = sections.map((s) => `<a href="#${s.id}" data-id="${s.id}">${esc(s.title)}</a>`).join('');
const body = sections
  .map((s) => `<section id="${s.id}"><h2>${inline(s.title)}</h2>${s.html.join('\n')}</section>`)
  .join('\n');

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
:root {
  color-scheme: light;
  --bg: #fafaf8; --panel: #ffffff; --text: #1d2125; --muted: #5b6570; --line: #dfe3e7;
  --accent: #1f6feb; --accent-soft: #e6f0ff; --code-bg: #f1f3f5; --hub: #e8f1ff; --ui: #eaf7ee; --out: #fff4e0; --lb: #f1ecff;
}
[data-theme="dark"] {
  color-scheme: dark;
  --bg: #14171a; --panel: #1c2024; --text: #e6e9ec; --muted: #9aa5b0; --line: #2d343b;
  --accent: #6aa5ff; --accent-soft: #1b2a40; --code-bg: #262c32; --hub: #1b2a40; --ui: #1a3023; --out: #3a2d14; --lb: #2b2540;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; scroll-padding-top: 16px; }
body { margin: 0; background: var(--bg); color: var(--text); font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
header { max-width: 980px; margin: 0 auto; padding: 56px 16px 8px; }
h1 { font-size: clamp(1.6rem, 4vw, 2.3rem); line-height: 1.2; margin: 0 0 12px; }
.lead { color: var(--muted); max-width: 70ch; margin: 0 0 20px; }
.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 12px; margin: 0 0 8px; }
.stat { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; }
.stat b { display: block; font-size: 0.8rem; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); }
.stat span { font-size: 1.05rem; font-weight: 600; }
main { max-width: 980px; margin: 0 auto; padding: 8px 16px 96px; }
section { padding-top: 20px; }
h2 { font-size: 1.5rem; margin: 28px 0 10px; padding-bottom: 6px; border-bottom: 1px solid var(--line); }
h3 { font-size: 1.15rem; margin: 22px 0 8px; }
p, li { max-width: 78ch; }
a { color: var(--accent); overflow-wrap: anywhere; }
code { background: var(--code-bg); padding: 1px 5px; border-radius: 4px; font: 0.9em ui-monospace, SFMono-Regular, Consolas, monospace; overflow-wrap: anywhere; }
pre { background: var(--code-bg); padding: 12px 14px; border-radius: 8px; overflow-x: auto; }
pre code { background: none; padding: 0; }
.tablewrap { overflow-x: auto; margin: 12px 0 18px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
table { border-collapse: collapse; width: 100%; font-size: 0.93rem; }
th, td { text-align: left; vertical-align: top; padding: 9px 12px; border-bottom: 1px solid var(--line); }
th { background: var(--accent-soft); font-weight: 600; white-space: nowrap; }
tr:last-child td { border-bottom: 0; }
.diagram { margin: 18px 0 22px; padding: 18px; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.dg-row { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; align-items: center; width: 100%; }
.dg-box { background: var(--code-bg); border: 1px solid var(--line); border-radius: 10px; padding: 10px 14px; text-align: center; min-width: 130px; flex: 0 1 190px; }
.dg-box b { display: block; }
.dg-box small { display: block; color: var(--muted); font-size: 0.8rem; line-height: 1.35; margin-top: 2px; }
.dg-hub { background: var(--hub); flex: 1 1 280px; max-width: 340px; border-color: var(--accent); }
.dg-lb { background: var(--lb); }
.dg-ui { background: var(--ui); }
.dg-out { background: var(--out); }
.dg-arrow { color: var(--muted); font-size: 0.9rem; text-align: center; }
.dg-side { font-size: 1.4rem; }
.shots { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr)); gap: 16px; margin: 14px 0 20px; align-items: start; }
.shots figure { margin: 0; }
.shots img { display: block; width: 100%; height: auto; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
.shots figcaption { color: var(--muted); font-size: 0.85rem; margin-top: 6px; }
.shots .narrow img { max-width: 260px; }
.dg-note { color: var(--muted); font-size: 0.85rem; text-align: center; margin-top: 4px; }
@media (max-width: 560px) { .dg-side { transform: rotate(90deg); } .dg-main { flex-direction: column; } .dg-main .dg-box { flex: 0 0 auto; width: 100%; max-width: 340px; } }
#theme { position: fixed; top: 12px; right: 12px; z-index: 20; border: 1px solid var(--line); background: var(--panel); color: var(--text); border-radius: 999px; padding: 6px 12px; cursor: pointer; font: inherit; font-size: 0.85rem; }
#navbtn { position: fixed; right: 16px; bottom: 16px; z-index: 20; border: 0; background: var(--accent); color: #fff; border-radius: 999px; padding: 10px 16px; cursor: pointer; font: inherit; font-weight: 600; box-shadow: 0 2px 10px rgba(0,0,0,.25); }
#navpanel { position: fixed; right: 16px; bottom: 64px; z-index: 20; width: min(340px, calc(100vw - 32px)); max-height: 70vh; overflow-y: auto; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 8px; box-shadow: 0 6px 24px rgba(0,0,0,.25); display: none; }
#navpanel.open { display: block; }
#navpanel a { display: block; padding: 7px 10px; border-radius: 8px; text-decoration: none; color: var(--text); font-size: 0.93rem; }
#navpanel a:hover { background: var(--code-bg); }
#navpanel a.current { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
</style>
</head>
<body>
<button id="theme" type="button" aria-label="Switch between light and dark">Dark mode</button>
<header>
  <h1>${esc(title)}</h1>
  <p class="lead">${inline(lead)}</p>
  <div class="stats">
${stats.map(([k, v]) => `<div class="stat"><b>${esc(k)}</b><span>${esc(v)}</span></div>`).join('\n')}
  </div>
</header>
<main>
${body}
</main>
<button id="navbtn" type="button" aria-expanded="false" aria-controls="navpanel">Sections</button>
<nav id="navpanel" aria-label="Sections">${nav}</nav>
<script>
(function () {
  var root = document.documentElement;
  var themeBtn = document.getElementById('theme');
  function apply(t) {
    if (t === 'dark') root.setAttribute('data-theme', 'dark'); else root.removeAttribute('data-theme');
    themeBtn.textContent = t === 'dark' ? 'Light mode' : 'Dark mode';
  }
  var saved = null;
  try { saved = localStorage.getItem('${themeKey}'); } catch (e) {}
  apply(saved === 'dark' ? 'dark' : 'light');
  themeBtn.addEventListener('click', function () {
    var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    apply(next);
    try { localStorage.setItem('${themeKey}', next); } catch (e) {}
  });

  var btn = document.getElementById('navbtn');
  var panel = document.getElementById('navpanel');
  function setOpen(open) { panel.classList.toggle('open', open); btn.setAttribute('aria-expanded', String(open)); }
  btn.addEventListener('click', function (e) { e.stopPropagation(); setOpen(!panel.classList.contains('open')); });
  panel.addEventListener('click', function (e) { if (e.target.tagName === 'A') setOpen(false); });
  document.addEventListener('click', function (e) { if (!panel.contains(e.target)) setOpen(false); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') setOpen(false); });

  var links = {};
  panel.querySelectorAll('a').forEach(function (a) { links[a.getAttribute('data-id')] = a; });
  function mark(id) {
    Object.keys(links).forEach(function (k) { links[k].classList.toggle('current', k === id); });
  }
  // The current section is the last one whose top has reached the upper part of the window.
  var sectionEls = Array.prototype.slice.call(document.querySelectorAll('main section'));
  var ticking = false;
  function update() {
    ticking = false;
    var current = null;
    sectionEls.forEach(function (s) { if (s.getBoundingClientRect().top <= 120) current = s.id; });
    mark(current);
  }
  function onScroll() { if (!ticking) { ticking = true; requestAnimationFrame(update); } }
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll);
  update();
})();
</script>
</body>
</html>
`;

writeFileSync(outPath, html);
console.log(`wrote ${outPath}: ${sections.length} sections, ${html.length} bytes`);
