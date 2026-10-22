// Servidor puente camara -> WLED. Sin dependencias (solo modulos de Node).
// Sirve index.html y reenvia los colores recibidos del navegador a WLED por UDP (DRGB, puerto 21324).
// Uso:  node server.js         (luego abre http://localhost:8099)
//       WLED_IP=192.168.x.x PORT=8099 node server.js
const http = require('http');
const dgram = require('dgram');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const PORT = parseInt(process.env.PORT || '8099', 10);
const DEFAULT_WLED_IP = process.env.WLED_IP || '';
const WLED_PORT = 21324;

const sock = dgram.createSocket('udp4');
const indexHtml = fs.readFileSync(path.join(__dirname, 'index.html'));

function clamp(v) { v = v | 0; return v < 0 ? 0 : v > 255 ? 255 : v; }

const server = http.createServer((req, res) => {
  const u = new URL(req.url, `http://${req.headers.host}`);

  if (req.method === 'GET' && (u.pathname === '/' || u.pathname === '/index.html')) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(indexHtml);
  }

  // El navegador envia {ip, leds:[[r,g,b],...]} ~15-20 veces/seg. Lo empaquetamos DRGB y a WLED.
  if (req.method === 'POST' && u.pathname === '/leds') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString());
        const ip = (body.ip || DEFAULT_WLED_IP || '').trim();
        const leds = body.leds;
        if (ip && Array.isArray(leds) && leds.length) {
          const buf = Buffer.alloc(2 + leds.length * 3);
          buf[0] = 2; // protocolo DRGB
          buf[1] = 2; // timeout 2 s (igual que WledUdpClient.java de la app)
          let p = 2;
          for (const c of leds) { buf[p++] = clamp(c[0]); buf[p++] = clamp(c[1]); buf[p++] = clamp(c[2]); }
          sock.send(buf, WLED_PORT, ip);
        }
        res.writeHead(200); res.end('ok');
      } catch (e) {
        res.writeHead(400); res.end('bad');
      }
    });
    return;
  }

  res.writeHead(404); res.end();
});

server.listen(PORT, () => {
  console.log(`\n  Camera Ambilight -> abre:  http://localhost:${PORT}\n`);
  console.log(`  WLED_IP por defecto: ${DEFAULT_WLED_IP || '(se pone en la pagina)'}\n`);
});
