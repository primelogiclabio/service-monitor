// Adds a demo server "docker01" with container checks declared by its own (fake) agent, then keeps it reporting.
// Usage: node seed-containers.mjs --hub http://127.0.0.1:8091 --token <ADMIN token>   (a THROWAWAY hub with svcmon.agent-declared-checks on)
const args = Object.fromEntries(process.argv.slice(2).reduce((a, x, i, all) => (x.startsWith('--') ? [...a, [x.slice(2), all[i + 1]]] : a), []));
const hub = args.hub.replace(/\/+$/, '');
const admin = args.token;

async function call(method, path, token, body) {
  const res = await fetch(`${hub}/api/v1${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${data?.message ?? text}`);
  return data;
}

const enroll = await call('POST', '/enrollment-tokens', admin, { ttlMinutes: 10 });
const reg = await call('POST', '/agent/register', enroll.token, { name: 'docker01', cluster: 'prod', os: 'ubuntu 24.04 (amd64)', agentVersion: '0.1.0' });
const agent = { token: reg.token };

const declared = await call('PUT', '/agent/checks', agent.token, {
  checks: [
    { type: 'CONTAINER', target: 'web', name: 'web (nginx)', intervalSeconds: 30 },
    { type: 'CONTAINER', target: 'api', name: 'api', intervalSeconds: 30, failuresBeforeDown: 2 },
    { type: 'CONTAINER', target: 'postgres', name: 'postgres', intervalSeconds: 30 },
    { type: 'CONTAINER', target: 'worker', name: 'worker', intervalSeconds: 30 },
    { type: 'CPU', config: { warn: 80, crit: 95 } },
    { type: 'MEMORY', config: { warn: 85, crit: 95 } },
    { type: 'DISK', target: '/', config: { warn: 80, crit: 90 } },
  ],
});
const cfg = await call('GET', '/agent/config', agent.token);
const byTarget = Object.fromEntries(cfg.checks.map((c) => [`${c.type}:${c.target ?? ''}`, c.id]));
console.log('declared', declared.results.map((r) => r.result).join(', '));

const hash = (s) => { let h = 2166136261; for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619); return (h >>> 0) / 4294967296; };
const noise = (k, t) => hash(k + Math.floor(t / 30000)) * 2 - 1;
const round1 = (v) => Math.round(v * 10) / 10;
const upFor = { web: [3 * 24 + 4, 'nginx:1.27'], api: [26, 'ghcr.io/acme/api:2.4.1'], postgres: [9 * 24, 'postgres:17'] };

function readings(t, now) {
  const out = [];
  for (const [name, [hours, image]] of Object.entries(upFor)) {
    const up = hours + (t - now) / 3600000;
    const text = up >= 48 ? `${Math.floor(up / 24)}d${Math.floor(up % 24)}h` : `${Math.floor(up)}h${Math.floor((up % 1) * 60)}m`;
    const health = name === 'api' ? 'healthy, ' : name === 'postgres' ? 'healthy, ' : '';
    out.push({ checkId: byTarget[`CONTAINER:${name}`], ts: t, status: 'UP', message: `${name} (${image}): running, ${health}up ${text}, 0 restart(s)` });
  }
  const downSince = now - 12 * 60000;
  if (t >= downSince) out.push({ checkId: byTarget['CONTAINER:worker'], ts: t, status: 'DOWN', message: `worker (ghcr.io/acme/worker:2.4.1): exited with code 137 (out of memory), ${Math.max(1, Math.round((t - downSince) / 60000))}m ago` });
  else out.push({ checkId: byTarget['CONTAINER:worker'], ts: t, status: 'UP', message: `worker (ghcr.io/acme/worker:2.4.1): running, up 2d3h, 1 restart(s)` });
  const cpu = round1(Math.max(2, 26 + 8 * Math.sin(t / 3.3e6) + 4 * noise('c', t)));
  const mem = round1(Math.min(99, 61 + 1.5 * noise('m', t)));
  out.push({ checkId: byTarget['CPU:'], ts: t, status: 'UP', value: cpu, message: `CPU ${cpu.toFixed(1)}% used` });
  out.push({ checkId: byTarget['MEMORY:'], ts: t, status: 'UP', value: mem, message: `memory ${mem.toFixed(1)}% used (12.4 GiB free of 32.0 GiB)` });
  out.push({ checkId: byTarget['DISK:/'], ts: t, status: 'UP', value: 46.2, message: '/ 46.2% used' });
  return out;
}

async function post(results) {
  for (let i = 0; i < results.length; i += 900) await call('POST', '/agent/report', agent.token, { os: 'ubuntu 24.04 (amd64)', agentVersion: '0.1.0', results: results.slice(i, i + 900) });
}

const now = Date.now();
const history = [];
for (let t = now - 3 * 3600000; t < now; t += 30000) history.push(...readings(t, now));
await post(history);
console.log(`posted ${history.length} historic results; reporting live every 10 s (Ctrl+C to stop)`);
for (;;) {
  await new Promise((r) => setTimeout(r, 10000));
  await post(readings(Date.now(), Date.now()));
}
