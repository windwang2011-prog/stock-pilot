// test/live.ts
// 端到端验证：真实行情 -> 盯盘引擎 -> 信号 + 通知决策 + 盘后报告
// 运行： node --experimental-strip-types test/live.ts

import { NodeHttp } from '../platform/node/nodeHttp.ts';
import { DataSource } from '../core/datasource.ts';
import { MarketEngine } from '../core/engine.ts';
import { marketPhase } from '../core/strategy.ts';
import { buildDailyReport } from '../core/report.ts';
import type { StockRef } from '../core/types.ts';

const WATCH: StockRef[] = [
  { secid: '1.600519', code: '600519', name: '贵州茅台' },
  { secid: '0.300750', code: '300750', name: '宁德时代' },
  { secid: '0.000858', code: '000858', name: '五粮液' },
  { secid: '1.601398', code: '601398', name: '工商银行' }
];

const ds = new DataSource(new NodeHttp());
const engine = new MarketEngine(ds);

function pct(v: number): string { return (v >= 0 ? '+' : '') + v.toFixed(2) + '%'; }
function money(v: number | null): string {
  if (v == null) return '--';
  const a = Math.abs(v);
  const s = v > 0 ? '+' : '-';
  if (a >= 1e8) return s + (a / 1e8).toFixed(2) + '亿';
  if (a >= 1e4) return s + (a / 1e4).toFixed(1) + '万';
  return s + a.toFixed(0);
}

async function main(): Promise<void> {
  const now = Date.now();
  const phase = marketPhase(now);
  console.log(`\n当前阶段：${phase.label}（${phase.note}）`);
  console.log(`是否需盯盘：${engine.shouldScan(now) ? '是' : '否'}｜是否到盘后汇总时间：${engine.isReportWindow(now) ? '是' : '否'}\n`);

  console.log('--- 拉取行情与K线，执行分时段分析 ---');
  const t0 = Date.now();
  const results = await engine.scan(WATCH, now);
  console.log(`扫描完成：${results.length} 只，用时 ${Date.now() - t0}ms\n`);

  for (const r of results) {
    const sig = r.signal;
    console.log(`${r.stock.name}(${r.stock.code})  现价 ${r.price.toFixed(2)}  ${pct(r.changePct)}`);
    console.log(`   动作：${sig.action}   综合分 ${sig.score}（日线 ${sig.baseScore} ${sig.sessionDelta >= 0 ? '+' : ''}${sig.sessionDelta}）`);
    console.log(`   主力净额：${money(r.mainNet)}   量比：${sig.metrics.volRatio == null ? '--' : sig.metrics.volRatio}`);
    console.log(`   依据：${sig.sessionSummary}`);
    const tech = sig.reasons.filter(x => x.indexOf('【') !== 0).slice(0, 2).join('；');
    if (tech) console.log(`   技术面：${tech}`);
    if (r.notify) console.log(`   🔔 将推送通知：${r.notifyTitle}`);
    console.log('');
  }

  console.log('--- 生成盘后汇总报告 ---\n');
  const date = new Date(now).toISOString().slice(0, 10);
  const rep = buildDailyReport(date, results, marketPhase(now));
  console.log(rep.text);
}

main().catch((e: unknown) => {
  console.error('运行失败：', e);
  process.exit(1);
});
