// core/strategy.ts
// 策略引擎：指标计算 + 日线技术面 + 盘前/盘中/盘后分时段分析 + 回测。
// 纯函数、无 I/O，可被 ArkTS(鸿蒙) 与 Node(测试) 共用。

import type { Kline, Quote, Signal, PhaseInfo, SessionPoint, Metrics, BacktestResult, Trade, EquityPoint, BacktestPoint } from './types';

// ---------------- 基础指标 ----------------
function ema(arr: number[], period: number): number[] {
  const k = 2 / (period + 1);
  const res: number[] = [];
  let prev = 0;
  for (let i = 0; i < arr.length; i++) {
    const v = arr[i];
    if (i === 0) { prev = v; res.push(v); }
    else { prev = v * k + prev * (1 - k); res.push(prev); }
  }
  return res;
}

function ma(arr: number[], period: number): (number | null)[] {
  const res: (number | null)[] = [];
  for (let i = 0; i < arr.length; i++) {
    if (i < period - 1) { res.push(null); continue; }
    let sum = 0;
    for (let j = i - period + 1; j <= i; j++) sum += arr[j];
    res.push(sum / period);
  }
  return res;
}

function rsi(closes: number[], period: number): (number | null)[] {
  const res: (number | null)[] = [];
  let gain = 0, loss = 0;
  for (let i = 0; i < closes.length; i++) {
    if (i === 0) { res.push(null); continue; }
    const diff = closes[i] - closes[i - 1];
    if (i <= period) {
      if (diff >= 0) gain += diff; else loss -= diff;
      if (i === period) {
        gain /= period; loss /= period;
        res.push(loss === 0 ? 100 : 100 - 100 / (1 + gain / loss));
      } else res.push(null);
    } else {
      const g = diff >= 0 ? diff : 0;
      const l = diff < 0 ? -diff : 0;
      gain = (gain * (period - 1) + g) / period;
      loss = (loss * (period - 1) + l) / period;
      res.push(loss === 0 ? 100 : 100 - 100 / (1 + gain / loss));
    }
  }
  return res;
}

export interface Indicators {
  closes: number[];
  ma5: (number | null)[];
  ma10: (number | null)[];
  ma20: (number | null)[];
  ma60: (number | null)[];
  dif: number[];
  dea: number[];
  macd: number[];
  rsi14: (number | null)[];
  volumes: number[];
  volMa5: (number | null)[];
}

export function computeIndicators(klines: Kline[]): Indicators {
  const closes = klines.map(k => k.close);
  const volumes = klines.map(k => k.volume);
  const ema12 = ema(closes, 12);
  const ema26 = ema(closes, 26);
  const dif = closes.map((_v, i) => ema12[i] - ema26[i]);
  const dea = ema(dif, 9);
  const macd = dif.map((d, i) => (d - dea[i]) * 2);
  return {
    closes,
    ma5: ma(closes, 5), ma10: ma(closes, 10), ma20: ma(closes, 20), ma60: ma(closes, 60),
    dif, dea, macd,
    rsi14: rsi(closes, 14),
    volumes, volMa5: ma(volumes, 5)
  };
}

function round2(v: number): number { return Math.round(v * 100) / 100; }
function round4(v: number): number { return Math.round(v * 10000) / 10000; }
function isNum(v: unknown): v is number { return typeof v === 'number' && isFinite(v); }
function toNum(v: unknown): number | null {
  if (isNum(v)) return v;
  if (typeof v === 'string' && v !== '' && isFinite(Number(v))) return Number(v);
  return null;
}

export function calcATR(klines: Kline[], period: number): number {
  if (klines.length < 2) return 0;
  const trs: number[] = [];
  for (let i = 1; i < klines.length; i++) {
    const h = klines[i].high, l = klines[i].low, pc = klines[i - 1].close;
    trs.push(Math.max(h - l, Math.abs(h - pc), Math.abs(l - pc)));
  }
  const slice = trs.slice(-period);
  let sum = 0;
  for (const t of slice) sum += t;
  const atr = sum / slice.length;
  return atr || klines[klines.length - 1].close * 0.03;
}

// ---------------- 日线技术面 ----------------
export interface BaseSignal {
  score: number;
  action: string;
  trend: string;
  uptrend: boolean;
  belowMA20: boolean;
  atr: number;
  entry: number;
  stop: number;
  target: number;
  rsi: number | null;
  macd: number;
  volRatio: number;
  reasons: string[];
}

export function analyze(klines: Kline[], quotePrice: number | null): BaseSignal {
  const ind = computeIndicators(klines);
  const n = klines.length;
  const reasons: string[] = [];
  let score = 50;
  const last = n - 1;

  const price = quotePrice != null && isNum(quotePrice) ? quotePrice : ind.closes[last];
  const prevPrice = ind.closes[last - 1];
  const c = ind.closes[last];
  const m5 = ind.ma5[last], m10 = ind.ma10[last], m20 = ind.ma20[last], m60 = ind.ma60[last];
  const m20Prev = ind.ma20[last - 1];
  const d = ind.dif[last], dea = ind.dea[last];
  const macdBar = ind.macd[last], macdPrev = ind.macd[last - 1];
  const r = ind.rsi14[last];
  const vol = ind.volumes[last], volM = ind.volMa5[last];
  const volRatio = volM ? vol / volM : 1;

  if (m5 != null && m10 != null && m20 != null && m60 != null) {
    if (c > m5 && m5 > m10 && m10 > m20 && m20 > m60) {
      score += 10; reasons.push('均线多头排列（价>MA5>MA10>MA20>MA60）');
    } else if (c > m20 && m20 > m60) {
      score += 5; reasons.push('价格站上 MA20，中期趋势向上');
    } else if (c < m20) {
      score -= 8; reasons.push('价格跌破 MA20，趋势偏弱');
    }
  }

  const m5p = ind.ma5[last - 1], m20p = ind.ma20[last - 1];
  if (m5 != null && m5p != null && m20 != null && m20p != null) {
    if (m5p <= m20p && m5 > m20) { score += 12; reasons.push('MA5 上穿 MA20 金叉（买入信号）'); }
    if (m5p >= m20p && m5 < m20) { score -= 12; reasons.push('MA5 下穿 MA20 死叉（卖出信号）'); }
  }

  if (d > dea) { score += 6; reasons.push('MACD 金叉区域（DIF>DEA）'); }
  else { score -= 6; reasons.push('MACD 死叉区域（DIF<DEA）'); }
  if (macdBar > 0 && macdBar > macdPrev) { score += 5; reasons.push('MACD 红柱放大，动能增强'); }
  else if (macdBar < 0 && macdBar < macdPrev) { score -= 5; reasons.push('MACD 绿柱放大，动能走弱'); }

  if (r != null) {
    if (r < 30) { score += 8; reasons.push(`RSI=${r.toFixed(0)} 超卖，有反弹需求`); }
    else if (r > 70) { score -= 10; reasons.push(`RSI=${r.toFixed(0)} 超买，注意回落`); }
    else if (r >= 45 && r <= 60) { score += 3; reasons.push(`RSI=${r.toFixed(0)} 健康区间`); }
  }

  if (volRatio > 1.5) { score += 4; reasons.push(`放量（量比 ${volRatio.toFixed(2)}），资金活跃`); }
  else if (volRatio < 0.6) { score -= 2; reasons.push(`缩量（量比 ${volRatio.toFixed(2)}）`); }

  const dayChg = prevPrice ? (price - prevPrice) / prevPrice : 0;
  if (dayChg > 0.03 && volRatio > 1.2) { score += 4; reasons.push('放量上涨，强势'); }
  if (dayChg < -0.03) { score -= 4; reasons.push('当日明显下跌'); }

  score = Math.max(0, Math.min(100, Math.round(score)));

  const uptrend = !!(m20 != null && m60 != null && m20Prev != null && c > m20 && m20 > m60 && m20 >= m20Prev);
  const belowMA20 = m20 != null && c < m20;
  const extended = m20 != null ? c > m20 * 1.12 : false;
  const illiquid = volRatio < 0.4;

  let action: string;
  if (score >= 70) action = '买入';
  else if (score >= 55) action = '逢低关注';
  else if (score >= 40) action = '持有观望';
  else if (score >= 25) action = '减仓';
  else action = '卖出';

  if (action === '买入' && !uptrend) { action = '持有观望'; reasons.push('未处于多头趋势（需价>MA20>MA60 且 MA20 向上），抑制买入'); }
  if (action === '买入' && extended) { action = '逢低关注'; reasons.push('股价偏离 MA20 过远（>12%），追高规避'); }
  if (action === '买入' && illiquid) { action = '逢低关注'; reasons.push('量能过低（量比<0.4），流动性不足暂缓'); }

  const atr = calcATR(klines, 14);
  const entry = price;
  const stop = m20 != null ? Math.min(m20, price - atr * 1.5) : price * 0.93;
  const target = entry + (entry - stop) * 2;
  const trend = (m20 != null && m60 != null) ? (c > m20 && m20 > m60 ? '多头' : (c < m20 ? '空头' : '震荡')) : '震荡';

  return {
    score, action, trend, uptrend, belowMA20,
    atr: round4(atr),
    entry: round2(entry), stop: round2(stop), target: round2(target),
    rsi: r != null ? round2(r) : null,
    macd: round4(macdBar), volRatio: round2(volRatio),
    reasons
  };
}

// ---------------- 市场阶段 ----------------
export function marketPhase(nowMs?: number): PhaseInfo {
  const d = nowMs ? new Date(nowMs) : new Date();
  const utcMs = d.getTime() + d.getTimezoneOffset() * 60000;
  const bj = new Date(utcMs + 8 * 3600000);
  const day = bj.getDay();
  const hm = bj.getHours() * 100 + bj.getMinutes();
  if (day === 0 || day === 6) return { phase: 'closed', label: '休市', note: '周末休市' };
  if (hm < 915) return { phase: 'pre', label: '盘前', note: '开盘前，以昨日技术面为主' };
  if (hm < 930) return { phase: 'auction', label: '集合竞价', note: '9:15-9:25 竞价撮合，关注开盘强弱' };
  if (hm < 1130) return { phase: 'intraday', label: '盘中', note: '上午交易时段' };
  if (hm < 1300) return { phase: 'lunch', label: '午间休市', note: '午间休市，参考上午表现' };
  if (hm < 1500) return { phase: 'intraday', label: '盘中', note: '下午交易时段' };
  if (hm < 1530) return { phase: 'post', label: '盘后', note: '盘后固定价交易时段' };
  return { phase: 'after', label: '收盘后', note: '收盘后复盘，参考全天资金与位置' };
}

// ---------------- 分时段综合分析 ----------------
export function sessionAnalyze(klines: Kline[], quote: Quote | null, phaseInfo?: PhaseInfo): Signal {
  const q: Quote = quote ? quote : { secid: '', code: '', market: '' };
  const info = phaseInfo ? phaseInfo : marketPhase();
  const phase = info.phase;
  const base = analyze(klines, toNum(q.price));

  const points: SessionPoint[] = [];
  let delta = 0;
  const add = (tag: string, text: string, impact: number): void => {
    delta += impact;
    points.push({ tag, text, impact });
  };

  const price = toNum(q.price) != null ? (toNum(q.price) as number) : base.entry;
  const prevClose = toNum(q.prevClose);
  const open = toNum(q.open);
  const high = toNum(q.high);
  const low = toNum(q.low);
  const volume = toNum(q.volume);
  const amount = toNum(q.amount);
  const volRatio = toNum(q.volumeRatio);
  const speed = toNum(q.speed);
  const mainNet = toNum(q.mainNet);
  const mainPct = toNum(q.mainPct);
  const changePct = toNum(q.changePct);
  const turnover = toNum(q.turnover);
  const amplitude = toNum(q.amplitude);

  let avg = toNum(q.avg);
  if (avg == null && amount != null && volume != null && volume > 0) avg = amount / (volume * 100);
  const pos = (high != null && low != null && high > low) ? (price - low) / (high - low) : null;
  const gap = (prevClose != null && open != null && prevClose !== 0) ? (open - prevClose) / prevClose * 100 : null;

  if ((phase === 'pre' || phase === 'auction') && gap != null) {
    if (gap >= 1 && gap <= 5) add('竞价', `高开 ${gap.toFixed(2)}%，竞价偏强`, 6);
    else if (gap > 5) add('竞价', `大幅高开 ${gap.toFixed(2)}%，警惕高开低走`, -4);
    else if (gap <= -3) add('竞价', `低开 ${gap.toFixed(2)}%，短线偏弱`, -6);
    else if (gap <= -1) add('竞价', `小幅低开 ${gap.toFixed(2)}%`, -2);
  }

  if (phase === 'intraday' || phase === 'lunch' || phase === 'post' || phase === 'after') {
    const cp = changePct != null ? changePct : 0;
    if (avg != null && avg !== 0 && price) {
      const dAvg = (price - avg) / avg * 100;
      if (dAvg > 0.5) add('分时', `现价高于当日均价 ${dAvg.toFixed(2)}%，多头占优`, 6);
      else if (dAvg < -0.5) add('分时', `现价低于当日均价 ${(-dAvg).toFixed(2)}%，走势偏弱`, -6);
    }
    if (volRatio != null) {
      if (volRatio >= 1.5 && cp > 0) add('量能', `量比 ${volRatio.toFixed(2)}，放量上涨`, 5);
      else if (volRatio >= 2 && cp < 0) add('量能', `量比 ${volRatio.toFixed(2)}，放量下跌`, -6);
      else if (volRatio < 0.6) add('量能', `量比 ${volRatio.toFixed(2)}，交投清淡`, -2);
    }
    if (speed != null) {
      if (speed >= 0.5) add('涨速', `涨速 +${speed.toFixed(2)}%/分，快速拉升`, 4);
      else if (speed <= -0.5) add('涨速', `涨速 ${speed.toFixed(2)}%/分，快速回落`, -4);
    }
    if (pos != null) {
      if (pos >= 0.7) add('位置', `处于当日振幅上沿(${(pos * 100).toFixed(0)}%)，收盘/现价强势`, 4);
      else if (pos <= 0.3) add('位置', `处于当日振幅下沿(${(pos * 100).toFixed(0)}%)，弱势`, -4);
    }
    if (prevClose != null && high != null && (high - price) / prevClose > 0.03 && cp < 2) {
      add('形态', '自当日高点回落超3%，警惕冲高回落', -5);
    }
    if (mainNet != null) {
      const yi = mainNet / 1e8;
      const pctTxt = mainPct != null ? `（占成交额 ${mainPct.toFixed(1)}%）` : '';
      if (mainNet > 0 && (mainPct == null || mainPct > 3)) add('资金', `主力净流入 ${yi.toFixed(2)} 亿${pctTxt}`, 7);
      else if (mainNet < 0 && (mainPct == null || mainPct < -3)) add('资金', `主力净流出 ${Math.abs(yi).toFixed(2)} 亿${pctTxt}`, -7);
    }
    if (turnover != null && turnover > 15) add('换手', `换手率 ${turnover.toFixed(1)}%，交投活跃`, 2);
  }

  if ((phase === 'pre' || phase === 'auction') && base.uptrend) {
    add('趋势', '日线维持多头趋势，盘前形态占优', 4);
  }

  const score = Math.max(0, Math.min(100, Math.round(base.score + delta)));
  let action: string;
  if (score >= 70) action = '买入';
  else if (score >= 55) action = '逢低关注';
  else if (score >= 40) action = '持有观望';
  else if (score >= 25) action = '减仓';
  else action = '卖出';

  const sessionSummary = points.length ? points.map(p => p.text).join('；') : '暂无分时特征';

  const metrics: Metrics = {
    gap: gap != null ? round2(gap) : null,
    avg: avg != null ? round2(avg) : null,
    position: pos != null ? round2(pos) : null,
    volRatio, speed, mainNet, mainPct, turnover, amplitude, changePct
  };

  const full: Signal = {
    score, baseScore: base.score, sessionDelta: delta, action,
    trend: base.trend, uptrend: base.uptrend, belowMA20: base.belowMA20, atr: base.atr,
    entry: base.entry, stop: base.stop, target: base.target,
    rsi: base.rsi, macd: base.macd, volRatio: base.volRatio,
    phase, phaseLabel: info.label,
    sessionSummary, sessionPoints: points,
    reasons: points.map(p => `【${p.tag}】${p.text}`).concat(base.reasons),
    metrics
  };
  return full;
}

// ---------------- 板块热度 ----------------
export function sectorHotness(changePct: number, mainFund: number, turnover: number): number {
  let score = (changePct || 0) * 3;
  score += Math.min(20, (mainFund || 0) / 1e8 * 0.5);
  score += Math.min(15, (turnover || 0) * 1.5);
  return Math.round(score * 10) / 10;
}

// ---------------- 回测（严格无未来函数） ----------------
export function backtest(klines: Kline[], days: number, opts?: { trailing?: boolean; breakeven?: boolean; timeStopDays?: number }): BacktestResult {
  const window = days || 22;
  const useTrail = !opts || opts.trailing !== false;
  const useBE = !opts || opts.breakeven !== false;
  const timeStop = (opts && opts.timeStopDays) || 15;
  const total = klines.length;
  if (total < 30) return { error: 'K线数据不足（需≥30根日K）' };

  let startIdx = Math.max(0, total - window);
  if (startIdx < 60) startIdx = 0;

  let cash = 1;
  let holding: { idx: number; entryDate: string; entryPrice: number; stop: number; target: number; atr: number; trailStop: number; score: number } | null = null;
  let runMaxClose = 0;
  const trades: Trade[] = [];
  const points: BacktestPoint[] = [];
  const equity: EquityPoint[] = [];

  const calDays = (a: string, b: string): number => {
    const d1 = new Date(a + 'T00:00:00').getTime();
    const d2 = new Date(b + 'T00:00:00').getTime();
    if (isNaN(d1) || isNaN(d2)) return 0;
    return Math.max(0, Math.round((d2 - d1) / 86400000));
  };

  for (let i = startIdx; i < total; i++) {
    const prefix = klines.slice(0, i + 1);
    const sig = analyze(prefix, prefix[prefix.length - 1].close);
    const bar = klines[i];

    if (!holding) {
      if (sig.action === '买入') {
        holding = {
          idx: i, entryDate: bar.date, entryPrice: bar.close,
          stop: sig.stop, target: sig.target, atr: sig.atr,
          trailStop: sig.stop, score: sig.score
        };
        runMaxClose = bar.close;
        points.push({ date: bar.date, index: i, type: 'buy', price: bar.close, score: sig.score });
      }
    } else {
      runMaxClose = Math.max(runMaxClose, bar.close);
      let exitPrice: number | null = null;
      let exitType = '';
      let exitLabel = '';

      let stopNow = holding.trailStop;
      if (useTrail) {
        const trail = runMaxClose - 2.5 * (holding.atr || 0);
        stopNow = Math.max(stopNow, trail);
        if (useBE && (runMaxClose - holding.entryPrice) >= (holding.atr || 0)) {
          stopNow = Math.max(stopNow, holding.entryPrice);
        }
        holding.trailStop = stopNow;
      }

      if (bar.low <= holding.trailStop) { exitPrice = holding.trailStop; exitType = 'stop'; exitLabel = '移动止损'; }
      else if (bar.high >= holding.target) { exitPrice = holding.target; exitType = 'target'; exitLabel = '止盈'; }
      else if (sig.belowMA20 || sig.action === '卖出') {
        exitPrice = bar.close; exitType = 'signal';
        exitLabel = sig.belowMA20 ? '跌破MA20离场' : '信号卖出';
      } else if (calDays(holding.entryDate, bar.date) >= timeStop) {
        exitPrice = bar.close; exitType = 'time'; exitLabel = '时间止损';
      }

      if (exitPrice != null) {
        const pnl = (exitPrice - holding.entryPrice) / holding.entryPrice;
        cash *= (1 + pnl);
        trades.push({
          entryDate: holding.entryDate, entryPrice: round2(holding.entryPrice),
          exitDate: bar.date, exitPrice: round2(exitPrice),
          type: exitType, typeLabel: exitLabel,
          pnlPct: Number((pnl * 100).toFixed(2)),
          holdingDays: calDays(holding.entryDate, bar.date)
        });
        points.push({ date: bar.date, index: i, type: 'sell', price: exitPrice, exitType });
        holding = null;
        runMaxClose = 0;
      }
    }

    const eq = holding ? cash * (1 + (bar.close - holding.entryPrice) / holding.entryPrice) : cash;
    equity.push({ date: bar.date, equity: Number(eq.toFixed(6)) });
  }

  if (holding) {
    const lastBar = klines[total - 1];
    const pnl = (lastBar.close - holding.entryPrice) / holding.entryPrice;
    trades.push({
      entryDate: holding.entryDate, entryPrice: round2(holding.entryPrice),
      exitDate: lastBar.date, exitPrice: round2(lastBar.close),
      type: 'open', typeLabel: '持仓中',
      pnlPct: Number((pnl * 100).toFixed(2)),
      holdingDays: calDays(holding.entryDate, lastBar.date)
    });
  }

  const closed = trades.filter(t => t.type !== 'open');
  const win = closed.filter(t => t.pnlPct > 0);
  const lastEq = equity.length ? equity[equity.length - 1].equity : 1;
  let peak = -Infinity, maxDD = 0;
  for (const e of equity) { peak = Math.max(peak, e.equity); maxDD = Math.max(maxDD, (peak - e.equity) / (peak || 1)); }

  return {
    stats: {
      windowDays: total - startIdx,
      startDate: klines[startIdx].date,
      endDate: klines[total - 1].date,
      tradeCount: trades.length,
      closedCount: closed.length,
      winCount: win.length,
      winRate: closed.length ? Number((win.length / closed.length * 100).toFixed(1)) : 0,
      totalReturnPct: Number(((lastEq - 1) * 100).toFixed(2)),
      avgPnlPct: closed.length ? Number((closed.reduce((a, t) => a + t.pnlPct, 0) / closed.length).toFixed(2)) : 0,
      maxWinPct: closed.length ? Number(Math.max.apply(null, closed.map(t => t.pnlPct)).toFixed(2)) : 0,
      maxLossPct: closed.length ? Number(Math.min.apply(null, closed.map(t => t.pnlPct)).toFixed(2)) : 0,
      maxDrawdownPct: Number((maxDD * 100).toFixed(2)),
      finalEquity: Number(lastEq.toFixed(4))
    },
    trades, equity, points
  };
}
