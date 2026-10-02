import http from 'node:http';
import path from 'node:path';
import { readFile, stat } from 'node:fs/promises';
const root = path.resolve('dist/frontend/browser');
const types = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};
http
  .createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost:4200');
      if (url.pathname === '/') {
        res.writeHead(302, { Location: '/MoneyMate/' });
        res.end();
        return;
      }
      if (!url.pathname.startsWith('/MoneyMate/')) {
        res.writeHead(404);
        res.end();
        return;
      }
      let file = path.resolve(
        root,
        '.' + decodeURIComponent(url.pathname.slice('/MoneyMate'.length)),
      );
      if (file !== root && !file.startsWith(root + path.sep)) throw Error('Invalid path');
      if ((await stat(file)).isDirectory()) file = path.join(file, 'index.html');
      res.writeHead(200, {
        'Content-Type': types[path.extname(file)] || 'application/octet-stream',
        'Cache-Control': 'no-cache',
      });
      res.end(await readFile(file));
    } catch {
      res.writeHead(404);
      res.end('Not found');
    }
  })
  .listen(4200, 'localhost', () =>
    console.log('MoneyMate production preview: http://localhost:4200/MoneyMate/'),
  );
