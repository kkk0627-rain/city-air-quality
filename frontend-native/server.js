/**
 * 零依赖静态服务器（仅用于本地预览原生版前端）。
 *
 * 为什么需要它：
 *  - 直接双击 index.html 会以 file:// 打开，浏览器把来源视为 null，
 *    后端 CorsConfig 不会放行，所有接口都会被拦；
 *  - 挂在 5174 是因为后端 CorsConfig 已显式放行
 *    http://localhost:5174 与 http://127.0.0.1:5174，无需改后端配置。
 *
 * 只做静态文件服务，不代理 /api —— 前端用 fetch 直连后端 8080，
 * 走的是真实跨域，正好能验证 CORS 配置是否生效。
 *
 * 用法：node server.js [port]
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const ROOT = __dirname;
const PORT = Number(process.argv[2]) || 5174;
const HOST = '127.0.0.1';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const server = http.createServer((req, res) => {
  let pathname;
  try {
    pathname = decodeURIComponent(url.parse(req.url).pathname);
  } catch (e) {
    res.writeHead(400).end('Bad Request');
    return;
  }

  if (pathname === '/') pathname = '/index.html';

  // 归一化后必须仍在 ROOT 内，防止 ../ 目录穿越
  const filePath = path.normalize(path.join(ROOT, pathname));
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403).end('Forbidden');
    return;
  }

  fs.stat(filePath, (err, stat) => {
    if (err || !stat.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found: ' + pathname);
      return;
    }

    const type = MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream';
    res.writeHead(200, {
      'Content-Type': type,
      'Cache-Control': 'no-cache'
    });
    fs.createReadStream(filePath).pipe(res);
  });
});

server.listen(PORT, HOST, () => {
  console.log('原生版前端已启动： http://' + HOST + ':' + PORT + '/');
  console.log('接口直连后端：   ' + (process.env.AQI_API_BASE || 'http://127.0.0.1:8080/api'));
});

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error('端口 ' + PORT + ' 已被占用，换一个：node server.js 5175');
  } else {
    console.error(err.message);
  }
  process.exit(1);
});
