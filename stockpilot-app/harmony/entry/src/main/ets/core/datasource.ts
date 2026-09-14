// core/datasource.ts
// 行情数据层：东方财富为主、腾讯为备用（不同域名，可绕开对 eastmoney 的定向拦截）。
// 多候选地址自动降级 + 按域名熔断 + 内存缓存。网络通过注入的 HttpClient，可跨平台。

import type { HttpClient } from './http';
import type { Kline, Quote } from './types';

const HEADERS: Record<string, string> = {
  'User-Agent': 'Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36',
  'Referer': 'https://quote.eastmoney.com/'
};

// 备用主机 / 协议降级表
const ALT: Record<string, string[]> = {
  'push2.eastmoney.com': ['http://push2.eastmoney.com', 'https://push2delay.eastmoney.com', 'http://push2delay.eastmoney.com'],
  'push2his.eastmoney.com': ['http://push2his.eastmoney.com'],
  'web.ifzq.gtimg.cn': ['http://web.ifzq.gtimg.cn'],
  'qt.gtimg.cn': ['http://qt.gtimg.cn']
};

interface BreakerState {
  fails: number;
  until: number;
}

interface CacheEntry {
  t: number;
  v: string;
}

const BREAKER_THRESHOLD = 3;
const BREAKER_COOLDOWN = 30000;
const RETRIABLE = /ECONNRESET|ECONNREFUSED|ETIMEDOUT|EPIPE|socket hang up|timeout|EAI_AGAIN/i;

function hostOf(url: string): string {
  const m = url.match(/^https?:\/\/([^/]+)/);
  return m ? m[1] : 'unknown';
}

function altUrls(url: string): string[] {
  const host = hostOf(url);
  const alts = ALT[host];
  if (!alts || alts.length === 0) return [];
  const out: string[] = [];
  for (const base of alts) {
    const path = url.replace(/^https?:\/\/[^/]+/, '');
    out.push(base + path);
  }
  return out;
}

function errCode(e: unknown): string {
  if (e && typeof e === 'object') {
    const o = e as { code?: string; message?: string };
    return o.code || o.message || 'unknown';
  }
  return String(e);
}

function sleep(ms: number): Promise<void> {
  return new Promise<void>((r) => setTimeout(r, ms));
}

export class DataSource {
  private http: HttpClient;
  private breaker: Map<string, BreakerState> = new Map<string, BreakerState>();
  private cache: Map<string, CacheEntry> = new Map<string, CacheEntry>();

  constructor(http: HttpClient) {
    this.http = http;
  }

  // 带降级 / 重试 / 熔断 / 缓存的取文本
  async fetchText(url: string, timeoutMs: number, cacheTtlMs: number): Promise<string> {
    const key = url;
    const hit = this.cache.get(key);
    if (hit && Date.now() - hit.t < cacheTtlMs) return hit.v;

    const candidates: string[] = [url].concat(altUrls(url));
    let lastErr = 'network failed';

    for (let ci = 0; ci < candidates.length; ci++) {
      const cand = candidates[ci];
      const host = hostOf(cand);
      const st = this.breaker.get(host) || { fails: 0, until: 0 };
      if (Date.now() < st.until) { lastErr = host + ' 熔断冷却中'; continue; }

      const attempts = ci === 0 ? 2 : 1;
      let out: string | null = null;
      for (let i = 0; i < attempts; i++) {
        try {
          const res = await this.http.get(cand, timeoutMs, HEADERS);
          out = res.text;
          break;
        } catch (e) {
          lastErr = errCode(e);
          if (i < attempts - 1 && RETRIABLE.test(lastErr)) { await sleep(250); continue; }
          break;
        }
      }
      if (out !== null) {
        this.breaker.set(host, { fails: 0, until: 0 });
        this.cache.set(key, { t: Date.now(), v: out });
        return out;
      }
      st.fails++;
      if (st.fails >= BREAKER_THRESHOLD) st.until = Date.now() + BREAKER_COOLDOWN;
      this.breaker.set(host, st);
    }
    throw new Error(lastErr);
  }

  // ---------------- 实时报价 ----------------
  async getQuotes(secids: string[]): Promise<Quote[]> {
    if (secids.length === 0) return [];
    let list: Quote[] = [];
    try {
      list = await this.emQuotes(secids);
    } catch (e) {
      list = await this.tencentQuotes(secids);
    }
    return list;
  }

  private async emQuotes(secids: string[]): Promise<Quote[]> {
    const url = 'https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2'
      + '&fields=f1,f2,f3,f4,f5,f6,f7,f8,f10,f12,f13,f14,f15,f16,f17,f18,f22,f62,f184,f124'
      + '&secids=' + secids.join(',');
    const txt = await this.fetchText(url, 6000, 5000);
    const j = JSON.parse(txt);
    const diff = (j && j.data && j.data.diff) || [];
    const out: Quote[] = [];
    for (const d of diff) {
      if (!d || d.f2 == null || d.f2 === '-') continue;
      out.push({
        secid: String(d.f13) + '.' + String(d.f12),
        code: String(d.f12), market: String(d.f13), name: d.f14,
        price: d.f2, changePct: d.f3, change: d.f4,
        volume: d.f5, amount: d.f6, amplitude: d.f7, turnover: d.f8, volumeRatio: d.f10,
        high: d.f15, low: d.f16, open: d.f17, prevClose: d.f18,
        speed: d.f22, mainNet: d.f62, mainPct: d.f184, time: d.f124
      });
    }
    if (out.length === 0) throw new Error('empty quotes');
    return out;
  }

  private async tencentQuotes(secids: string[]): Promise<Quote[]> {
    const syms: { secid: string; sym: string }[] = [];
    for (const sid of secids) {
      const p = sid.split('.');
      syms.push({ secid: sid, sym: (p[0] === '1' ? 'sh' : 'sz') + p[1] });
    }
    const url = 'https://qt.gtimg.cn/q=' + syms.map(s => s.sym).join(',');
    const txt = await this.fetchText(url, 6000, 5000);
    const map: Record<string, Quote> = {};
    const segs = txt.split(';');
    for (const seg of segs) {
      const m = seg.match(/v_([a-z]{2}\d+)="([^"]*)"/);
      if (!m) continue;
      const f = m[2].split('~');
      if (f.length < 52) continue;
      const px = Number(f[3]);
      if (!(px > 0)) continue;
      const p2 = m[1].slice(2);
      const mkt = m[1].startsWith('sh') ? '1' : '0';
      map[m[1]] = {
        secid: mkt + '.' + p2, code: p2, market: mkt,
        price: px, prevClose: Number(f[4]), open: Number(f[5]), volume: Number(f[6]),
        change: Number(f[31]), changePct: Number(f[32]), high: Number(f[33]), low: Number(f[34]),
        amount: Number(f[37]) * 10000, turnover: Number(f[38]), amplitude: Number(f[43]),
        volumeRatio: Number(f[49]), avg: Number(f[51]), time: Date.now()
      };
    }
    const out: Quote[] = [];
    for (const item of syms) {
      const q = map[item.sym];
      if (q) out.push(q);
    }
    if (out.length === 0) throw new Error('empty tencent quotes');
    return out;
  }

  // ---------------- K 线 ----------------
  // klt: 101 日 / 102 周 / 103 月
  async getKlines(secid: string, limit: number, klt: number): Promise<Kline[]> {
    try {
      return await this.emKline(secid, limit, klt);
    } catch (e) {
      return await this.tencentKline(secid, limit, klt);
    }
  }

  private async emKline(secid: string, limit: number, klt: number): Promise<Kline[]> {
    const url = 'https://push2his.eastmoney.com/api/qt/stock/kline/get'
      + '?secid=' + secid + '&klt=' + klt + '&fqt=1&lmt=' + limit + '&end=20500101'
      + '&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61';
    const txt = await this.fetchText(url, 8000, 60000);
    const j = JSON.parse(txt);
    const kl = (j && j.data && j.data.klines) || [];
    const rows: Kline[] = [];
    for (const line of kl) {
      const p = String(line).split(',');
      const close = Number(p[2]);
      if (!(close > 0)) continue;
      rows.push({ date: p[0], open: Number(p[1]), close, high: Number(p[3]), low: Number(p[4]), volume: Number(p[5]) });
    }
    if (rows.length === 0) throw new Error('empty klines');
    return rows;
  }

  private async tencentKline(secid: string, limit: number, klt: number): Promise<Kline[]> {
    const period = klt === 102 ? 'week' : (klt === 103 ? 'month' : (klt === 101 ? 'day' : ''));
    if (!period) throw new Error('unsupported klt ' + klt);
    const p = secid.split('.');
    const sym = (p[0] === '1' ? 'sh' : 'sz') + p[1];
    const url = 'https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=' + sym + ',' + period + ',,,' + limit + ',qfq';
    const txt = await this.fetchText(url, 8000, 60000);
    const j = JSON.parse(txt);
    const node = j && j.data && j.data[sym];
    if (!node) throw new Error('empty tencent kline');
    const arr = node['qfq' + period] || node[period] || [];
    const rows: Kline[] = [];
    for (const r of arr) {
      const close = Number(r[2]);
      if (!(close > 0)) continue;
      rows.push({ date: r[0], open: Number(r[1]), close, high: Number(r[3]), low: Number(r[4]), volume: Number(r[5]) });
    }
    if (rows.length === 0) throw new Error('empty tencent klines');
    return rows;
  }

  // ---------------- 板块 ----------------
  async getHotSectors(): Promise<{ code: string; name: string; changePct: number; mainFund: number; turnover: number }[]> {
    const url = 'https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=120&po=1&np=1&fltt=2&invt=2&fid=f3'
      + '&fs=m:90+t:3&fields=f12,f14,f3,f62,f184,f6';
    const txt = await this.fetchText(url, 8000, 60000);
    const j = JSON.parse(txt);
    const list = (j && j.data && j.data.diff) || [];
    const out: { code: string; name: string; changePct: number; mainFund: number; turnover: number }[] = [];
    for (const d of list) {
      out.push({ code: String(d.f12), name: d.f14, changePct: d.f3, mainFund: d.f62, turnover: d.f184 });
    }
    if (out.length === 0) throw new Error('empty sectors');
    return out;
  }

  async getSectorStocks(code: string): Promise<{ code: string; market: string; name: string; changePct: number; mainFund: number }[]> {
    const url = 'https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3'
      + '&fs=b:' + code + '&fields=f12,f13,f14,f3,f62,f184,f6';
    const txt = await this.fetchText(url, 8000, 60000);
    const j = JSON.parse(txt);
    const list = (j && j.data && j.data.diff) || [];
    const out: { code: string; market: string; name: string; changePct: number; mainFund: number }[] = [];
    for (const d of list) {
      out.push({ code: String(d.f12), market: String(d.f13), name: d.f14, changePct: d.f3, mainFund: d.f62 });
    }
    return out;
  }

  // ---------------- 搜索（代码 / 名称 / 拼音） ----------------
  async searchStocks(kw: string): Promise<{ code: string; market: string; name: string; secid: string }[]> {
    const token = 'D43BF7224FB9E8E14DAEAFBAB648853A';
    const url = 'https://searchapi.eastmoney.com/api/suggest/get?input=' + encodeURIComponent(kw)
      + '&type=14&token=' + token + '&client=PC&src=EM_quote&version=2019';
    const txt = await this.fetchText(url, 6000, 60000);
    const j = JSON.parse(txt);
    const rows = (j && j.QuotationCodeTable && j.QuotationCodeTable.Data) || [];
    const out: { code: string; market: string; name: string; secid: string }[] = [];
    for (const r of rows) {
      if (!r || !r.Code || !r.Name) continue;
      const code = String(r.Code);
      if (!/^\d/.test(code)) continue;
      const market = code.startsWith('6') ? '1' : '0';
      out.push({ code, market, name: r.Name, secid: market + '.' + code });
      if (out.length >= 15) break;
    }
    return out;
  }
}
