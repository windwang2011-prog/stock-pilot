// core/types.ts
// 平台无关的共享类型定义。同时被 ArkTS(鸿蒙) / Node 测试 使用。
// 注意：刻意不使用 enum / namespace / 装饰器，以兼容 Node 类型擦除 与 ArkTS 严格模式。

export interface Kline {
  date: string;
  open: number;
  close: number;
  high: number;
  low: number;
  volume: number;
}

export interface Quote {
  secid: string;
  code: string;
  market: string;
  name?: string;
  price?: number;
  change?: number;
  changePct?: number;
  prevClose?: number;
  open?: number;
  high?: number;
  low?: number;
  volume?: number;
  amount?: number;
  amplitude?: number;
  turnover?: number;
  volumeRatio?: number;
  speed?: number;
  mainNet?: number;
  mainPct?: number;
  avg?: number;
  time?: number;
}

export interface SessionPoint {
  tag: string;
  text: string;
  impact: number;
}

export interface Metrics {
  gap: number | null;
  avg: number | null;
  position: number | null;
  volRatio: number | null;
  speed: number | null;
  mainNet: number | null;
  mainPct: number | null;
  turnover: number | null;
  amplitude: number | null;
  changePct: number | null;
}

export interface Signal {
  score: number;
  baseScore: number;
  sessionDelta: number;
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
  phase: string;
  phaseLabel: string;
  sessionSummary: string;
  sessionPoints: SessionPoint[];
  reasons: string[];
  metrics: Metrics;
}

export interface PhaseInfo {
  phase: string;
  label: string;
  note: string;
}

export interface StockRef {
  secid: string;
  code: string;
  name: string;
}

export interface Trade {
  entryDate: string;
  entryPrice: number;
  exitDate: string;
  exitPrice: number;
  type: string;
  typeLabel: string;
  pnlPct: number;
  holdingDays: number;
}

export interface EquityPoint {
  date: string;
  equity: number;
}

export interface BacktestPoint {
  date: string;
  index: number;
  type: string;
  price: number;
  exitType?: string;
  score?: number;
}

export interface BacktestStats {
  windowDays: number;
  startDate: string;
  endDate: string;
  tradeCount: number;
  closedCount: number;
  winCount: number;
  winRate: number;
  totalReturnPct: number;
  avgPnlPct: number;
  maxWinPct: number;
  maxLossPct: number;
  maxDrawdownPct: number;
  finalEquity: number;
}

export interface BacktestResult {
  error?: string;
  stats?: BacktestStats;
  trades?: Trade[];
  equity?: EquityPoint[];
  points?: BacktestPoint[];
}

// 盯盘结果（含通知决策所需信息）
export interface WatchResult {
  stock: StockRef;
  signal: Signal;
  price: number;
  changePct: number;
  mainNet: number | null;
  notify: boolean;          // 本次是否需要推送
  notifyTitle: string;
  notifyBody: string;
}
