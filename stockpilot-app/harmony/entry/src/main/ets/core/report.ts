// core/report.ts
// 盘后汇总报告生成（纯函数，便于在鸿蒙端直接渲染或保存）。

import type { WatchResult, PhaseInfo } from './types';

function fmtMoney(v: number | null): string {
  if (v == null || !isFinite(v)) return '--';
  const a = Math.abs(v);
  const sign = v > 0 ? '+' : (v < 0 ? '-' : '');
  if (a >= 1e8) return sign + (a / 1e8).toFixed(2) + '亿';
  if (a >= 1e4) return sign + (a / 1e4).toFixed(1) + '万';
  return sign + a.toFixed(0);
}

function fmtPct(v: number): string {
  if (v == null || !isFinite(v)) return '--';
  return (v >= 0 ? '+' : '') + v.toFixed(2) + '%';
}

export interface DailyReport {
  date: string;
  title: string;
  text: string;          // 可在通知/分享中使用的纯文本
  buyCount: number;
  sellCount: number;
  watchCount: number;
  topUp: WatchResult[];
  topDown: WatchResult[];
}

export function buildDailyReport(date: string, results: WatchResult[], phase: PhaseInfo): DailyReport {
  const buy = results.filter(r => r.signal.action === '买入');
  const sell = results.filter(r => r.signal.action === '卖出');
  const sorted = results.slice().sort((a, b) => b.changePct - a.changePct);
  const topUp = sorted.slice(0, 5);
  const topDown = sorted.slice(-5).reverse().filter(r => topUp.indexOf(r) < 0);

  const lines: string[] = [];
  lines.push(`# StockPilot 盘后汇总 ${date}`);
  lines.push('');
  lines.push(`市场阶段：${phase.label}（${phase.note}）`);
  lines.push(`自选数量：${results.length}｜买入信号：${buy.length}｜卖出信号：${sell.length}`);
  lines.push('');

  if (buy.length > 0) {
    lines.push('## 买入信号');
    for (const r of buy) {
      lines.push(`- ${r.stock.name}(${r.stock.code}) 现价 ${r.price.toFixed(2)} ${fmtPct(r.changePct)}｜评分 ${r.signal.score}｜主力 ${fmtMoney(r.mainNet)}`);
      lines.push(`  依据：${r.signal.sessionSummary}`);
      const tech = r.signal.reasons.filter(x => x.indexOf('【') !== 0).slice(0, 2).join('；');
      if (tech) lines.push(`  技术面：${tech}`);
    }
    lines.push('');
  }

  if (sell.length > 0) {
    lines.push('## 卖出信号');
    for (const r of sell) {
      lines.push(`- ${r.stock.name}(${r.stock.code}) 现价 ${r.price.toFixed(2)} ${fmtPct(r.changePct)}｜评分 ${r.signal.score}｜主力 ${fmtMoney(r.mainNet)}`);
      lines.push(`  依据：${r.signal.sessionSummary}`);
    }
    lines.push('');
  }

  lines.push('## 自选表现（涨幅前五）');
  for (const r of topUp) {
    lines.push(`- ${r.stock.name} ${fmtPct(r.changePct)}｜评分 ${r.signal.score}｜${r.signal.action}`);
  }
  lines.push('');
  lines.push('## 自选表现（跌幅前五）');
  for (const r of topDown) {
    lines.push(`- ${r.stock.name} ${fmtPct(r.changePct)}｜评分 ${r.signal.score}｜${r.signal.action}`);
  }
  lines.push('');
  lines.push('> 数据来自公开行情接口，策略为技术指标分析，仅供参考，不构成投资建议。');

  const text = lines.join('\n');
  return {
    date,
    title: `StockPilot 盘后汇总 ${date}（买${buy.length}/卖${sell.length}）`,
    text,
    buyCount: buy.length,
    sellCount: sell.length,
    watchCount: results.length,
    topUp, topDown
  };
}
