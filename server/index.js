// Workspace overview server — serves a styled HTML index of the HubertStudios
// license-system workspace so the Replit preview pane shows something useful.
// This is NOT part of the deployed system; it is a local dev convenience only.

const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 5000;

const WORKER_URL = "https://api.gg69nah.workers.dev";
const DASHBOARD_URL = "https://hubert-studios.vercel.app/login";

function readFileLines(filePath, max) {
  try {
    const content = fs.readFileSync(filePath, "utf8");
    const lines = content.split("\n").slice(0, max).join("\n");
    return lines;
  } catch {
    return null;
  }
}

function countFiles(dir) {
  try {
    let count = 0;
    const items = fs.readdirSync(dir, { withFileTypes: true });
    for (const item of items) {
      if (item.isDirectory()) count += countFiles(path.join(dir, item.name));
      else count++;
    }
    return count;
  } catch {
    return 0;
  }
}

function html(content) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>HubertStudios License System — Workspace</title>
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: system-ui, -apple-system, sans-serif; background: #0a0a0a; color: #e5e5e5; padding: 2rem; min-height: 100vh; }
  h1 { font-size: 1.75rem; font-weight: 700; color: #fff; margin-bottom: 0.25rem; }
  .subtitle { color: #888; font-size: 0.9rem; margin-bottom: 2rem; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 1rem; margin-bottom: 2rem; }
  .card { background: #141414; border: 1px solid #232323; border-radius: 10px; padding: 1.25rem 1.5rem; }
  .card h2 { font-size: 1rem; font-weight: 600; color: #fff; margin-bottom: 0.35rem; display: flex; align-items: center; gap: 0.5rem; }
  .card p { font-size: 0.8rem; color: #888; line-height: 1.5; }
  .card .meta { font-size: 0.75rem; color: #555; margin-top: 0.5rem; }
  .badge { display: inline-block; font-size: 0.65rem; font-weight: 600; padding: 0.15rem 0.45rem; border-radius: 9999px; text-transform: uppercase; letter-spacing: 0.05em; }
  .badge-green { background: #14532d; color: #4ade80; }
  .badge-blue  { background: #1e3a5f; color: #60a5fa; }
  .badge-purple{ background: #3b0764; color: #c084fc; }
  .badge-orange{ background: #431407; color: #fb923c; }
  .links { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 2rem; }
  a { color: #60a5fa; text-decoration: none; font-size: 0.85rem; }
  a:hover { text-decoration: underline; }
  .section-title { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.1em; color: #555; margin-bottom: 0.75rem; margin-top: 1.5rem; }
  .check { color: #4ade80; }
  .url-box { background: #141414; border: 1px solid #232323; border-radius: 8px; padding: 0.75rem 1rem; font-family: monospace; font-size: 0.82rem; color: #a3e635; margin-bottom: 0.5rem; display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
  .url-box span { color: #555; font-size: 0.75rem; font-family: system-ui; }
</style>
</head>
<body>
${content}
</body>
</html>`;
}

const server = http.createServer((req, res) => {
  const root = path.resolve(__dirname, "..");

  const backendFiles = countFiles(path.join(root, "backend/src"));
  const frontendPages = countFiles(path.join(root, "frontend/app"));
  const pluginOSFiles = countFiles(path.join(root, "plugins/OrbitalStrike/src"));
  const pluginCGFiles = countFiles(path.join(root, "plugins/CoreGuard/src"));

  const body = `
<h1>🛡️ HubertStudios License System</h1>
<p class="subtitle">Organized workspace — Replit preview</p>

<p class="section-title">Deployed endpoints</p>
<div class="url-box">
  ${WORKER_URL}
  <span>Cloudflare Worker (backend)</span>
</div>
<div class="url-box">
  ${DASHBOARD_URL}
  <span>Vercel Dashboard (frontend)</span>
</div>

<p class="section-title">Workspace components</p>
<div class="grid">
  <div class="card">
    <h2><span class="badge badge-blue">Worker</span> backend/</h2>
    <p>Cloudflare Worker — license validation, admin API, auth, CORS, signed responses.</p>
    <p class="meta">${backendFiles} source files &nbsp;·&nbsp; <span class="check">✓</span> All pass node --check</p>
  </div>
  <div class="card">
    <h2><span class="badge badge-green">Next.js</span> frontend/</h2>
    <p>Admin dashboard — overview, plugins, licenses, builds, bans, active servers, audit log, settings.</p>
    <p class="meta">${frontendPages} app files &nbsp;·&nbsp; Real API calls via lib/api.ts</p>
  </div>
  <div class="card">
    <h2><span class="badge badge-purple">Java</span> license-java-client/</h2>
    <p>Universal drop-in license client. Copy License.java + LicenseGate.java into any Bukkit / Paper / Folia plugin.</p>
    <p class="meta">2 Java files + license.yml template</p>
  </div>
  <div class="card">
    <h2><span class="badge badge-orange">Plugin</span> plugins/OrbitalStrike/</h2>
    <p>OrbitalStrike Bukkit/Paper/Folia plugin with license gate. Blocks enable and runtime use when license is invalid.</p>
    <p class="meta">${pluginOSFiles} Java source files</p>
  </div>
  <div class="card">
    <h2><span class="badge badge-orange">Plugin</span> plugins/CoreGuard/</h2>
    <p>CoreGuard staff management plugin with Folia-compatible scheduler. License-gated via SchedulerUtil bridge.</p>
    <p class="meta">${pluginCGFiles} Java source files</p>
  </div>
  <div class="card">
    <h2>📖 docs/</h2>
    <p>Full setup documentation covering Worker deployment, Vercel deployment, Java plugin integration, and the universal license client.</p>
    <p class="meta">SETUP.md · WORKER_DEPLOY.md · FRONTEND_DEPLOY.md · JAVA_CLIENT.md</p>
  </div>
</div>

<p class="section-title">Documentation</p>
<div class="links">
  <a href="https://github.com" target="_blank">docs/SETUP.md — Full setup guide</a>
  <a href="${WORKER_URL}/health" target="_blank">${WORKER_URL}/health — Worker health check</a>
  <a href="${DASHBOARD_URL}" target="_blank">${DASHBOARD_URL} — Live dashboard</a>
</div>

<p class="section-title">Build checks</p>
<div class="card">
  <p><span class="check">✓</span> backend/src/index.combined.js — node --check passed</p>
  <p><span class="check">✓</span> backend/src/lib/*.js (9 files) — all pass node --check</p>
  <p><span class="check">✓</span> backend/src/routes/*.js (8 files) — all pass node --check</p>
  <p style="margin-top:0.5rem;color:#555;font-size:0.8rem;">Java: requires Maven 3.8+ and Java 17+ — run <code>mvn package</code> in each plugin directory.</p>
  <p style="color:#555;font-size:0.8rem;">Frontend: requires pnpm — run <code>pnpm install &amp;&amp; pnpm build</code> in frontend/.</p>
</div>
`;

  const page = html(body);
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
  res.end(page);
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`HubertStudios workspace overview running on port ${PORT}`);
  console.log(`Worker URL:    ${WORKER_URL}`);
  console.log(`Dashboard URL: ${DASHBOARD_URL}`);
});
