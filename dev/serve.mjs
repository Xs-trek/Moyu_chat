// Zero-dependency static server for the browser preview: `npm run dev`, then open http://localhost:5178/.
// Serves only index.html, src/ and dev/; the mock host replaces MoyuHost, so no native code runs here.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.PORT) || 5178;
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.json': 'application/json; charset=utf-8' };

createServer(async (req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  const relative = path === '/' ? 'dev/preview.html' : normalize(path.slice(1)).replace(/\\/g, '/');
  if (!/^(src|dev)\//.test(relative) || relative.includes('..')) { res.writeHead(404).end(); return; }
  try {
    const body = await readFile(join(root, relative));
    res.writeHead(200, { 'content-type': types[extname(relative)] || 'application/octet-stream', 'cache-control': 'no-store' });
    res.end(body);
  } catch { res.writeHead(404).end(); }
}).listen(port, () => console.log(`Moyu Chat preview: http://localhost:${port}/`));
