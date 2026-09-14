// platform/node/nodeHttp.ts
// Node 版 HttpClient 实现，仅用于开发期测试核心逻辑。
// 支持 HTTPS_PROXY（走 CONNECT 隧道）与 IPv4 优先。

import * as https from 'https';
import * as http from 'http';
import * as dns from 'dns';
import type { HttpClient, HttpResult } from '../../core/http.ts';

try { dns.setDefaultResultOrder('ipv4first'); } catch (e) { /* ignore */ }

const PROXY = (function (): { host: string; port: number; auth: string | null } | null {
  const raw = process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY || process.env.http_proxy;
  if (!raw) return null;
  try {
    const u = new URL(raw);
    return {
      host: u.hostname,
      port: parseInt(u.port || (u.protocol === 'https:' ? '443' : '80'), 10),
      auth: u.username ? 'Basic ' + Buffer.from(decodeURIComponent(u.username) + ':' + decodeURIComponent(u.password || '')).toString('base64') : null
    };
  } catch (e) { return null; }
})();

function collect(mod: typeof https | typeof http, req: http.ClientRequest, timeoutMs: number): Promise<HttpResult> {
  return new Promise<HttpResult>((resolve, reject) => {
    req.on('response', (res: http.IncomingMessage) => {
      const status = res.statusCode || 0;
      if (status >= 300) { res.resume(); reject(new Error('HTTP ' + status)); return; }
      let data = '';
      res.on('data', (c: Buffer) => { data += c.toString('utf8'); });
      res.on('end', () => resolve({ ok: true, status, text: data }));
    });
    req.on('error', reject);
    req.setTimeout(timeoutMs, () => req.destroy(new Error('timeout')));
    req.end();
  });
}

function viaProxy(url: string, headers: Record<string, string>, timeoutMs: number): Promise<HttpResult> {
  return new Promise<HttpResult>((resolve, reject) => {
    const u = new URL(url);
    const port = u.port || (u.protocol === 'https:' ? 443 : 80);
    const conn = http.request({
      host: PROXY ? PROXY.host : '', port: PROXY ? PROXY.port : 0, method: 'CONNECT',
      path: `${u.hostname}:${port}`,
      headers: PROXY && PROXY.auth ? { 'Proxy-Authorization': PROXY.auth } : {}
    });
    conn.on('connect', (res, socket) => {
      if (res.statusCode !== 200) { socket.destroy(); reject(new Error('proxy CONNECT ' + res.statusCode)); return; }
      const mod = url.startsWith('https') ? https : http;
      const req = mod.request({
        host: u.hostname, port, path: u.pathname + u.search,
        headers, socket, agent: false, servername: u.hostname
      });
      collect(mod, req, timeoutMs).then(resolve, reject);
    });
    conn.on('error', reject);
    conn.setTimeout(timeoutMs, () => conn.destroy(new Error('proxy timeout')));
    conn.end();
  });
}

function direct(url: string, headers: Record<string, string>, timeoutMs: number): Promise<HttpResult> {
  const u = new URL(url);
  const mod = url.startsWith('https') ? https : http;
  const req = mod.request({ host: u.hostname, port: u.port || (url.startsWith('https') ? 443 : 80), path: u.pathname + u.search, headers });
  return collect(mod, req, timeoutMs);
}

export class NodeHttp implements HttpClient {
  get(url: string, timeoutMs: number, headers: Record<string, string>): Promise<HttpResult> {
    return PROXY ? viaProxy(url, headers, timeoutMs) : direct(url, headers, timeoutMs);
  }
}
