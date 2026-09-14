// core/engine.ts
// 盯盘引擎：交易时段调度 + 拉取数据 + 分时段分析 + 通知决策（去重）。
// 平台无关：通知通过回调交给平台层（鸿蒙用 notificationManager，Node 用控制台）。

import { DataSource } from './datasource';
import { sessionAnalyze, marketPhase } from './strategy';
import type { Kline, Quote, StockRef, WatchResult, PhaseInfo } from './types';

const NOTIFY_ACTIONS: string[] = ['买入', '卖出'];

export class MarketEngine {
  private ds: DataSource;
  private lastAction: Map<string, string> = new Map<string, string>();

  constructor(ds: DataSource) {
    this.ds = ds;
  }

  // 是否处于需盯盘的交易时段（北京时间，工作日）
  shouldScan(nowMs: number): boolean {
    const p = marketPhase(nowMs);
    return p.phase === 'auction' || p.phase === 'intraday' || p.phase === 'lunch';
  }

  // 是否到达"盘后汇总"时间（15:30-16:00）
  isReportWindow(nowMs: number): boolean {
    const p = marketPhase(nowMs);
    if (p.phase !== 'post' && p.phase !== 'after') return false;
    const bj = beijingHM(nowMs);
    return bj >= 1530 && bj < 1600;
  }

  // 扫描一批自选股，返回含通知决策的结果
  async scan(stocks: StockRef[], nowMs?: number): Promise<WatchResult[]> {
    if (stocks.length === 0) return [];
    const phase: PhaseInfo = marketPhase(nowMs);

    let quotes: Quote[] = [];
    try {
      quotes = await this.ds.getQuotes(stocks.map(s => s.secid));
    } catch (e) { quotes = []; }
    const qmap: Record<string, Quote> = {};
    for (const q of quotes) qmap[q.secid] = q;

    const results: WatchResult[] = [];
    // 限流 3 并发，避免移动端瞬时压力过大
    const limit = 3;
    let idx = 0;
    const worker = async (): Promise<void> => {
      while (true) {
        const i = idx++;
        if (i >= stocks.length) break;
        const st = stocks[i];
        try {
          const klines: Kline[] = await this.ds.getKlines(st.secid, 120, 101);
          if (klines.length === 0) continue;
          const q = qmap[st.secid] || null;
          const sig = sessionAnalyze(klines, q, phase);
          const price = q && q.price != null ? q.price : klines[klines.length - 1].close;
          const changePct = q && q.changePct != null ? q.changePct : 0;

          const prev = this.lastAction.get(st.secid);
          const shouldNotify = prev !== sig.action && NOTIFY_ACTIONS.indexOf(sig.action) >= 0;
          this.lastAction.set(st.secid, sig.action);

          results.push({
            stock: st, signal: sig, price, changePct,
            mainNet: sig.metrics.mainNet,
            notify: shouldNotify,
            notifyTitle: `${st.name}(${st.code}) 信号：${sig.action}`,
            notifyBody: `${sig.phaseLabel} · 评分 ${sig.score}｜${sig.sessionSummary}`
          });
        } catch (e) { /* 单只失败不影响整体 */ }
      }
    };
    const workers: Promise<void>[] = [];
    for (let k = 0; k < Math.min(limit, stocks.length); k++) workers.push(worker());
    await Promise.all(workers);

    results.sort((a, b) => b.signal.score - a.signal.score);
    return results;
  }
}

function beijingHM(nowMs: number): number {
  const d = new Date(nowMs);
  const utcMs = d.getTime() + d.getTimezoneOffset() * 60000;
  const bj = new Date(utcMs + 8 * 3600000);
  return bj.getHours() * 100 + bj.getMinutes();
}
