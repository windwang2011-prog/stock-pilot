// test/logic.test.ts
// 离线逻辑校验：不联网，验证 阶段判定 / 技术面 / 分时段合成 / 回测 / 日报。
// 运行： node --experimental-strip-types test/logic.test.ts

import assert from 'node:assert/strict';
import { marketPhase, analyze, sessionAnalyze, backtest, sectorHotness } from '../core/strategy.ts';
import { buildDailyReport } from '../core/report.ts';
import type { Kline, Quote, WatchResult } from '../core/types.ts';

let passed = 0;
function ok(name: string, cond: boolean): void {
  assert.ok(cond, 'FAILED: ' + name);
  passed++;
  console.log('  ✓ ' + name);
}

const r2 = (v: number): number => Math.round(v * 100) / 100;

function genKlines(n: number, start: number, drift: number, vol: number): Kline[] {
  const out: Kline[] = [];
  let px = start;
  const d0 = Date.UTC(2026, 0, 1);
  for (let i = 0; i < n; i++) {
    px = px * (1 + drift);
    out.push({
      date: new Date(d0 + i * 86400000).toISOString().slice(0, 10),
      open: r2(px * 0.995), close: r2(px), high: r2(px * 1.012), low: r2(px * 0.988), volume: vol
    });
  }
  return out;
}

const up = genKlines(140, 10, 0.006, 100000);      // 稳步上升
const down = genKlines(140, 30, -0.006, 100000);   // 稳步下跌

console.log('\n[1] 市场阶段判定');
ok('10:30 -> 盘中', marketPhase(new Date('2026-09-14T10:30:00+08:00').getTime()).phase === 'intraday');
ok('09:20 -> 集合竞价', marketPhase(new Date('2026-09-14T09:20:00+08:00').getTime()).phase === 'auction');
ok('08:50 -> 盘前', marketPhase(new Date('2026-09-14T08:50:00+08:00').getTime()).phase === 'pre');
ok('12:00 -> 午间休市', marketPhase(new Date('2026-09-14T12:00:00+08:00').getTime()).phase === 'lunch');
ok('15:10 -> 盘后', marketPhase(new Date('2026-09-14T15:10:00+08:00').getTime()).phase === 'post');
ok('17:00 -> 收盘后', marketPhase(new Date('2026-09-14T17:00:00+08:00').getTime()).phase === 'after');
ok('周日 -> 休市', marketPhase(new Date('2026-09-13T10:00:00+08:00').getTime()).phase === 'closed');

console.log('\n[2] 日线技术面');
const aUp = analyze(up, null);
ok('上升趋势识别为多头', aUp.uptrend === true);
ok('评分在 0~100', aUp.score >= 0 && aUp.score <= 100);
ok('止损位低于入场价', aUp.stop < aUp.entry);
ok('目标位高于入场价', aUp.target > aUp.entry);
ok('包含技术面理由', aUp.reasons.length > 0);

const aDown = analyze(down, null);
ok('下跌趋势不给"买入"（趋势门禁生效）', aDown.action !== '买入');
ok('下跌趋势 uptrend=false', aDown.uptrend === false);

console.log('\n[3] 分时段综合分析（盘中）');
const lastClose = up[up.length - 1].close;
const quote: Quote = {
  secid: '1.600519', code: '600519', market: '1', name: '测试股',
  price: lastClose, prevClose: up[up.length - 2].close, open: lastClose * 0.99,
  high: lastClose * 1.02, low: lastClose * 0.985,
  volume: 100000, amount: lastClose * 100000 * 100 * 0.97,
  volumeRatio: 1.8, speed: 0.6, mainNet: 5e7, mainPct: 6.2, changePct: 2.5, turnover: 3.1
};
const intraday = marketPhase(new Date('2026-09-14T14:00:00+08:00').getTime());
const s1 = sessionAnalyze(up, quote, intraday);
ok('阶段标记正确', s1.phase === 'intraday' && s1.phaseLabel === '盘中');
ok('综合分 = base + delta（并裁剪 0~100）', s1.score === Math.max(0, Math.min(100, Math.round(s1.baseScore + s1.sessionDelta))));
ok('分时要点非空', s1.sessionPoints.length > 0);
ok('识别"高于均价"多头占优', s1.sessionSummary.indexOf('均价') >= 0);
ok('识别主力净流入', s1.sessionSummary.indexOf('主力净流入') >= 0);
ok('计算当日均价', s1.metrics.avg != null && s1.metrics.avg > 0);
ok('reasons 含【分时】前缀项', s1.reasons.some(r => r.indexOf('【') === 0));

console.log('\n[4] 分时段综合分析（盘前/集合竞价：看跳空）');
const pre = marketPhase(new Date('2026-09-14T09:20:00+08:00').getTime());
const gapQuote: Quote = { secid: '1.600519', code: '600519', market: '1', prevClose: 100, open: 103, price: 103 };
const s2 = sessionAnalyze(up, gapQuote, pre);
ok('识别高开加分', s2.sessionPoints.some(p => p.tag === '竞价' && p.impact > 0));
const gapLow: Quote = { secid: '1.600519', code: '600519', market: '1', prevClose: 100, open: 95, price: 95 };
const s3 = sessionAnalyze(up, gapLow, pre);
ok('识别低开减分', s3.sessionPoints.some(p => p.tag === '竞价' && p.impact < 0));

console.log('\n[5] 回测（无未来函数）');
const bt = backtest(up, 120);
ok('返回统计结果', bt.stats != null);
ok('窗口不超过 K 线数量', (bt.stats ? bt.stats.windowDays : 0) <= 140);
ok('总收益为数值', typeof (bt.stats ? bt.stats.totalReturnPct : null) === 'number');
ok('最大回撤非负', (bt.stats ? bt.stats.maxDrawdownPct : -1) >= 0);
const btShort = backtest(genKlines(20, 10, 0.01, 1000), 22);
ok('K线不足时返回错误', btShort.error != null);

console.log('\n[6] 板块热度');
ok('涨幅越高热度越高', sectorHotness(5, 1e8, 3) > sectorHotness(1, 1e8, 3));

console.log('\n[7] 盘后日报');
const results: WatchResult[] = [
  { stock: { secid: '1.600519', code: '600519', name: '测试A' }, signal: s1, price: lastClose, changePct: 2.5, mainNet: 5e7, notify: true, notifyTitle: '', notifyBody: '' },
  { stock: { secid: '0.000001', code: '000001', name: '测试B' }, signal: aDown as unknown as typeof s1, price: 10, changePct: -3.1, mainNet: -2e7, notify: false, notifyTitle: '', notifyBody: '' }
];
const rep = buildDailyReport('2026-09-14', results, marketPhase(new Date('2026-09-14T17:00:00+08:00').getTime()));
ok('报告含标题', rep.text.indexOf('# StockPilot 盘后汇总') === 0);
ok('报告含自选数量', rep.text.indexOf('自选数量：2') >= 0);
ok('报告含免责声明', rep.text.indexOf('不构成投资建议') > 0);
ok('统计买入信号数', rep.buyCount >= 0 && rep.sellCount >= 0);

console.log(`\n全部通过：${passed} 项断言 ✅`);
