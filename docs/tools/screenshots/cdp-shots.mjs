// Takes screenshots of the demo dashboard through a headless Edge's DevTools port, with a minimal WebSocket client.
// Usage: node cdp-shots.mjs <debugPort> <outDir> <baseUrl> <adminToken> <serverIdDocker> <serverIdWeb02>
// Only for a THROWAWAY demo hub (see README.md in this folder): it stores the token in the browser profile.
import http from 'node:http';
import crypto from 'node:crypto';
import { writeFileSync } from 'node:fs';

const [, , port, outDir, base, token, dockerId, web02Id] = process.argv;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function getJson(path) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port, path }, (res) => {
      let d = '';
      res.on('data', (c) => (d += c));
      res.on('end', () => resolve(JSON.parse(d)));
    }).on('error', reject);
  });
}

class Cdp {
  constructor(socket) {
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.id = 0;
    this.pending = new Map();
    socket.on('data', (c) => { this.buf = Buffer.concat([this.buf, c]); this.parse(); });
  }
  static connect(wsPath) {
    return new Promise((resolve, reject) => {
      const key = crypto.randomBytes(16).toString('base64');
      const req = http.request({ host: '127.0.0.1', port, path: wsPath, headers: { Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Key': key, 'Sec-WebSocket-Version': '13' } });
      req.on('upgrade', (res, socket, head) => { const c = new Cdp(socket); if (head.length) { c.buf = head; c.parse(); } resolve(c); });
      req.on('error', reject);
      req.end();
    });
  }
  parse() {
    for (;;) {
      if (this.buf.length < 2) return;
      let len = this.buf[1] & 0x7f;
      let off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (this.buf.length < 10) return; len = Number(this.buf.readBigUInt64BE(2)); off = 10; }
      if (this.buf.length < off + len) return;
      const opcode = this.buf[0] & 0x0f;
      const payload = this.buf.subarray(off, off + len);
      this.buf = this.buf.subarray(off + len);
      if (opcode === 1) {
        const msg = JSON.parse(payload.toString('utf8'));
        if (msg.id && this.pending.has(msg.id)) { const { resolve, reject } = this.pending.get(msg.id); this.pending.delete(msg.id); msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result); }
      }
    }
  }
  send(method, params = {}) {
    const id = ++this.id;
    const data = Buffer.from(JSON.stringify({ id, method, params }));
    const mask = crypto.randomBytes(4);
    let header;
    if (data.length < 126) header = Buffer.from([0x81, 0x80 | data.length]);
    else if (data.length < 65536) { header = Buffer.alloc(4); header[0] = 0x81; header[1] = 0x80 | 126; header.writeUInt16BE(data.length, 2); }
    else { header = Buffer.alloc(10); header[0] = 0x81; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(data.length), 2); }
    const masked = Buffer.from(data);
    for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
    this.socket.write(Buffer.concat([header, mask, masked]));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? 'evaluate failed');
    return r.result.value;
  }
}

const pages = await getJson('/json');
const page = pages.find((p) => p.type === 'page');
const cdp = await Cdp.connect(new URL(page.webSocketDebuggerUrl).pathname);
await cdp.send('Page.enable');

async function setView(width, height, scale) {
  await cdp.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile: width < 600 });
}
async function go(url, wait = 2500) {
  await cdp.send('Page.navigate', { url });
  await sleep(wait);
}
// Signs the demo session in and picks the theme by writing what the dashboard itself stores (keys sm-auth, sm-theme).
async function theme(t) {
  await go(`${base}/`, 1500);
  await cdp.eval(`localStorage.setItem('sm-auth', JSON.stringify({ hubUrl: location.origin, token: ${JSON.stringify(token)} })); localStorage.setItem('sm-theme', ${JSON.stringify(t)}); true`);
}
const clickText = (text, selector = 'button') =>
  cdp.eval(`(() => { const b = [...document.querySelectorAll(${JSON.stringify(selector)})].find(x => x.textContent.trim().replace(/^[^A-Za-z0-9]+/, '').toLowerCase().startsWith(${JSON.stringify(text.toLowerCase())})); if (!b) return false; b.click(); return true; })()`);
async function shot(name, { fullPage = false } = {}) {
  let params = { format: 'png' };
  if (fullPage) {
    const { contentSize } = await cdp.send('Page.getLayoutMetrics');
    params = { format: 'png', captureBeyondViewport: true, clip: { x: 0, y: 0, width: contentSize.width, height: Math.ceil(contentSize.height), scale: 1 } };
  }
  const r = await cdp.send('Page.captureScreenshot', params);
  writeFileSync(`${outDir}/${name}.png`, Buffer.from(r.data, 'base64'));
  console.log('saved', name);
}
const setField = (id, value, event) =>
  cdp.eval(`(() => { const e = document.getElementById(${JSON.stringify(id)}); if (!e) return false; e.value = ${JSON.stringify(value)}; e.dispatchEvent(new Event(${JSON.stringify(event)}, { bubbles: true })); return true; })()`);

for (const t of ['light', 'dark']) {
  await theme(t);
  await setView(1280, 1000, 1.5);
  await go(`${base}/#/`, 3000);
  await shot(`dashboard-${t}`);

  await go(`${base}/#/server/${web02Id}`, 3000);
  await clickText('CPU');
  await sleep(1500);
  await shot(`check-chart-${t}`, { fullPage: true });

  await go(`${base}/#/server/${dockerId}`, 3000);
  await clickText('worker');
  await sleep(1200);
  await shot(`check-container-${t}`, { fullPage: true });

  if (t === 'light') {
    await go(`${base}/#/checks`, 3000);
    await shot('monitors-light');

    await go(`${base}/#/server/${dockerId}`, 3000);
    await clickText('Add check');
    await sleep(800);
    await setField('cf-type', 'CONTAINER', 'change');
    await sleep(300);
    await setField('cf-name', 'web container', 'input');
    await setField('cf-target', 'web', 'input');
    await setField('cf-socket', '/var/run/docker.sock', 'input');
    await sleep(600);
    await shot('add-container-check-light');

    await setView(390, 900, 2);
    await go(`${base}/#/`, 3000);
    await shot('dashboard-mobile-light');
  }
}
await cdp.send('Browser.close').catch(() => {});
process.exit(0);
